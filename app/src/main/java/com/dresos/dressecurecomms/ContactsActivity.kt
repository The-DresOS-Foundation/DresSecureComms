/* Copyright © 2026 The DresOS Foundation. Licensed under the Apache License, Version 2.0. */
package com.dresos.dressecurecomms

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dresos.dressecurecomms.data.ContactsStore
import com.dresos.dressecurecomms.data.VCard
import com.dresos.dressecurecomms.databinding.ActivityContactsBinding
import com.dresos.dressecurecomms.ui.TwoLineAdapter
import androidx.core.widget.doAfterTextChanged
import com.dresos.dressecurecomms.util.applyScreenshotPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class ContactsActivity : AppCompatActivity() {
    private lateinit var b: ActivityContactsBinding
    private lateinit var adapter: TwoLineAdapter<ContactsStore.Contact>

    private val requestReadContacts =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) importDevice()
            else Snackbar.make(b.root, "Contacts permission denied", Snackbar.LENGTH_LONG).show()
        }

    private val pickVcf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importVcf(uri)
        }

    private val saveVcf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri ->
            if (uri != null) exportVcf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy()
        b = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.title = getString(R.string.card_contacts_title)
        b.toolbar.setNavigationIcon(R.drawable.ic_back)
        b.toolbar.setNavigationOnClickListener { finish() }

        adapter = TwoLineAdapter(this, emptyList()) { c ->
            val numLabel = if (c.extras.isEmpty()) c.number else "${c.number}  (+${c.extras.size})"
            Triple(c.name, if (c.email.isNotEmpty()) "$numLabel  ·  ${c.email}" else numLabel, "")
        }
        b.list.adapter = adapter
        b.list.emptyView = b.empty
        b.list.setOnItemClickListener { _, _, pos, _ -> contactActions(adapter.getItem(pos)) }
        b.fab.setOnClickListener { addMenu() }
        b.search.doAfterTextChanged { applyFilter() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private var all: List<ContactsStore.Contact> = emptyList()

    private fun refresh() {
        all = ContactsStore.load(this)
        applyFilter()
    }

    private fun applyFilter() {
        val q = b.search.text?.toString()?.trim()?.lowercase().orEmpty()
        adapter.setItems(
            if (q.isEmpty()) all
            else all.filter { c ->
                c.name.lowercase().contains(q) || c.numbers.any { it.number.lowercase().contains(q) }
            }
        )
    }

    private fun addMenu() {
        val options = arrayOf(
            getString(R.string.add_contact),
            getString(R.string.import_device),
            getString(R.string.import_file),
            getString(R.string.export_file)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_contact)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showForm(null)
                    1 -> requestReadContacts.launch(Manifest.permission.READ_CONTACTS)
                    2 -> pickVcf.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "*/*"))
                    3 -> saveVcf.launch("contacts.vcf")
                }
            }
            .show()
    }

    private fun importVcf(uri: android.net.Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            Snackbar.make(b.root, getString(R.string.import_failed), Snackbar.LENGTH_LONG).show()
            return
        }
        val found = VCard.parse(text)
        if (found.isEmpty()) {
            Snackbar.make(b.root, getString(R.string.no_contacts_in_file), Snackbar.LENGTH_LONG).show()
            return
        }
        ContactsStore.addAll(this, found)
        refresh()
        Snackbar.make(b.root, getString(R.string.imported_file, found.size), Snackbar.LENGTH_LONG).show()
    }

    private fun exportVcf(uri: android.net.Uri) {
        val contacts = ContactsStore.load(this)
        if (contacts.isEmpty()) {
            Snackbar.make(b.root, getString(R.string.no_contacts_to_export), Snackbar.LENGTH_LONG).show()
            return
        }
        val ok = try {
            contentResolver.openOutputStream(uri)?.use { it.write(VCard.write(contacts).toByteArray()) }
            true
        } catch (e: Exception) {
            false
        }
        val msg = if (ok) getString(R.string.exported_file, contacts.size) else getString(R.string.export_failed)
        Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
    }

    private fun showForm(existing: ContactsStore.Contact?) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val name = EditText(this).apply {
            hint = getString(R.string.name_hint); setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val email = EditText(this).apply {
            hint = getString(R.string.email_hint); setText(existing?.email ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        // One editable row per number. New contacts start with a single row; more are added on
        // demand with the button below, so unused number fields are never shown.
        val numbersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = ArrayList<Pair<EditText, Spinner>>()

        fun addRow(number: String, type: String) {
            val field = EditText(this).apply {
                hint = getString(R.string.number_hint); setText(number)
                inputType = InputType.TYPE_CLASS_PHONE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@ContactsActivity, android.R.layout.simple_spinner_item, ContactsStore.TYPES
                ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                val idx = ContactsStore.TYPES.indexOf(type)
                setSelection(if (idx < 0) 0 else idx)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(field); addView(spinner)
            }
            rows.add(field to spinner)
            numbersBox.addView(row)
        }

        val initial = existing?.numbers ?: listOf(ContactsStore.PhoneNumber("", ContactsStore.DEFAULT_TYPE))
        initial.forEach { addRow(it.number, it.type) }

        val addBtn = Button(this).apply {
            text = getString(R.string.add_number)
            setOnClickListener { addRow("", ContactsStore.DEFAULT_TYPE) }
        }

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
            addView(name); addView(numbersBox); addView(addBtn); addView(email)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_contact else R.string.edit_contact)
            .setView(ScrollView(this).apply { addView(wrap) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = name.text.toString().trim()
                val entered = rows.mapNotNull { (f, s) ->
                    val num = f.text.toString().trim()
                    if (num.isEmpty()) null
                    else ContactsStore.PhoneNumber(
                        num,
                        ContactsStore.TYPES.getOrElse(s.selectedItemPosition) { ContactsStore.DEFAULT_TYPE }
                    )
                }
                val e = email.text.toString().trim()
                if (n.isNotEmpty() && entered.isNotEmpty()) {
                    val primary = entered.first()
                    val updated = ContactsStore.Contact(n, primary.number, e, primary.type, entered.drop(1))
                    if (existing == null) ContactsStore.add(this, updated)
                    else ContactsStore.update(this, existing, updated)
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importDevice() {
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        // Group the device's phone rows by contact name so each person becomes one entry that
        // keeps all of their numbers, with the device's label mapped to our types.
        val byName = LinkedHashMap<String, ArrayList<ContactsStore.PhoneNumber>>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            val ni = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val ti = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (c.moveToNext()) {
                val n = c.getString(ni) ?: continue
                val p = c.getString(pi) ?: continue
                val label = phoneTypeLabel(c.getInt(ti))
                val forName = byName.getOrPut(n) { ArrayList() }
                if (forName.none { it.number == p }) forName.add(ContactsStore.PhoneNumber(p, label))
            }
        }
        val found = byName.mapNotNull { (name, nums) ->
            if (nums.isEmpty()) null
            else {
                val primary = nums.first()
                ContactsStore.Contact(name, primary.number, "", primary.type, nums.drop(1))
            }
        }
        ContactsStore.addAll(this, found)
        refresh()
        Snackbar.make(b.root, "Imported ${found.size} contacts", Snackbar.LENGTH_LONG).show()
    }

    private fun phoneTypeLabel(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK,
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "Fax"
        else -> "Other"
    }

    private fun pickNumber(c: ContactsStore.Contact, onPick: (String) -> Unit) {
        val nums = c.numbers
        if (nums.size <= 1) { onPick(c.number); return }
        val labels = nums.map { "${it.type}: ${it.number}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_number)
            .setItems(labels) { _, which -> onPick(nums[which].number) }
            .show()
    }

    private fun contactActions(c: ContactsStore.Contact) {
        val options = if (c.email.isNotEmpty())
            arrayOf("Message", "Call", "Email", "Edit", "Delete")
        else
            arrayOf("Message", "Call", "Edit", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Message" -> pickNumber(c) { num ->
                        startActivity(Intent(this, ThreadActivity::class.java).putExtra("address", num))
                    }
                    "Call" -> pickNumber(c) { num ->
                        startActivity(Intent(this, CallsActivity::class.java).putExtra("number", num))
                    }
                    "Email" -> startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:${c.email}")),
                            "Email"
                        )
                    )
                    "Edit" -> showForm(c)
                    "Delete" -> { ContactsStore.delete(this, c); refresh() }
                }
            }
            .show()
    }
}
