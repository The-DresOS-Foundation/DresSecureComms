/* Copyright © 2026 The DresOS Foundation. Licensed under the Apache License, Version 2.0. */
package com.dresos.dressecurecomms.data

import android.content.Context
import com.dresos.dressecurecomms.crypto.CryptoManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ContactsStore {
    private const val FILE = "contacts.dat"

    /** Phone-number types offered in the editor. The stored value is the label text. */
    val TYPES = arrayOf("Mobile", "Home", "Work", "Fax", "Other")
    const val DEFAULT_TYPE = "Mobile"

    data class PhoneNumber(val number: String, val type: String = DEFAULT_TYPE)

    /**
     * A saved contact.
     *
     * [number] is the primary number and [primaryType] its type; any further numbers live in
     * [extras]. The on-disk format keeps the primary number under the original "p" key and only
     * adds the new "pt" (primary type) and "x" (extra numbers) fields when they carry something.
     * So a vault written by an older build loads here unchanged, and an older build can still read
     * a vault written by this one: it reads "n"/"p"/"e" as before and simply ignores the extras.
     */
    data class Contact(
        val name: String,
        val number: String,
        val email: String = "",
        val primaryType: String = DEFAULT_TYPE,
        val extras: List<PhoneNumber> = emptyList()
    ) {
        /** Primary number first, then the extras, in display order. */
        val numbers: List<PhoneNumber>
            get() {
                val out = ArrayList<PhoneNumber>(extras.size + 1)
                out.add(PhoneNumber(number, primaryType))
                out.addAll(extras)
                return out
            }
    }

    fun load(ctx: Context): List<Contact> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(CryptoManager.decrypt(f.readText()))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val extras = ArrayList<PhoneNumber>()
                val x = o.optJSONArray("x")
                if (x != null) {
                    for (i in 0 until x.length()) {
                        val e = x.getJSONObject(i)
                        val en = e.optString("p", "")
                        if (en.isNotBlank()) extras.add(PhoneNumber(en, e.optString("t", DEFAULT_TYPE)))
                    }
                }
                Contact(
                    o.getString("n"),
                    o.getString("p"),
                    o.optString("e", ""),
                    o.optString("pt", DEFAULT_TYPE),
                    extras
                )
            }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, contact: Contact) {
        val list = load(ctx).toMutableList()
        if (list.none { it.name == contact.name && it.number == contact.number }) {
            list.add(contact)
            save(ctx, list)
        }
    }

    fun addAll(ctx: Context, contacts: List<Contact>) {
        val list = load(ctx).toMutableList()
        for (c in contacts) if (list.none { it.name == c.name && it.number == c.number }) list.add(c)
        save(ctx, list)
    }

    fun update(ctx: Context, original: Contact, updated: Contact) {
        var matched = false
        val list = load(ctx).map {
            if (!matched && it.name == original.name && it.number == original.number) {
                matched = true; updated
            } else it
        }.toMutableList()
        if (!matched) list.add(updated)
        save(ctx, list)
    }

    fun delete(ctx: Context, contact: Contact) {
        save(ctx, load(ctx).filterNot { it.name == contact.name && it.number == contact.number })
    }

    private fun save(ctx: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { c ->
            val o = JSONObject().put("n", c.name).put("p", c.number).put("e", c.email)
            if (c.primaryType != DEFAULT_TYPE) o.put("pt", c.primaryType)
            if (c.extras.isNotEmpty()) {
                val x = JSONArray()
                c.extras.forEach { pn ->
                    if (pn.number.isNotBlank()) x.put(JSONObject().put("p", pn.number).put("t", pn.type))
                }
                if (x.length() > 0) o.put("x", x)
            }
            arr.put(o)
        }
        File(ctx.filesDir, FILE).writeText(CryptoManager.encrypt(arr.toString()))
    }
}
