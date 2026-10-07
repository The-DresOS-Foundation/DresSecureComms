/* Copyright © 2026 The DresOS Foundation. Licensed under the Apache License, Version 2.0. */
package com.dresos.dressecurecomms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.DatabaseUtils
import android.net.Uri
import android.provider.Telephony.Mms
import androidx.preference.PreferenceManager
import com.android.mms.MmsConfig
import com.android.mms.transaction.DownloadManager
import com.android.mms.transaction.NotificationTransaction
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.DeliveryInd
import com.google.android.mms.pdu_alt.GenericPdu
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.ReadOrigInd
import com.klinker.android.send_message.Settings
import com.klinker.android.send_message.Transaction
import java.util.concurrent.Executors

/**
 * Drop-in replacement for com.android.mms.transaction.PushReceiver from the bundled
 * com.klinkerapps:android-smsmms library.
 *
 * The library's PushReceiver reaches the hidden framework class
 * android.database.sqlite.SqliteWrapper, whose static query(...) overload was removed in newer
 * Android. The framework copy shadows the one the library ships, so on Android 16 the call throws
 *   java.lang.NoSuchMethodError: No static method query(...) in class
 *   Landroid/database/sqlite/SqliteWrapper;
 * from PushReceiver.isDuplicateNotification, and the incoming MMS is never downloaded.
 *
 * This receiver performs the same work using ContentResolver directly (and skips the duplicate
 * check, which the library disabled by always returning false anyway), then hands off to the
 * library's DownloadManager so the existing download and MMS_RECEIVED completion pipeline — and
 * the app's own MmsReceiver — are unchanged.
 *
 * Reported by a user on a Samsung Galaxy S24 running Android 16. Thank you.
 */
class MmsPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isPush = action == "android.provider.Telephony.WAP_PUSH_DELIVER" ||
            action == "android.provider.Telephony.WAP_PUSH_RECEIVED"
        if (!isPush || !ContentType.MMS_MESSAGE.equals(intent.type, ignoreCase = true)) return

        val pushData = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra("subscription", Settings.DEFAULT_SUBSCRIPTION_ID)
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                MmsConfig.init(context)
                handle(context, pushData, subId)
            } catch (t: Throwable) {
                // A malformed push must never crash the receiver.
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, pushData: ByteArray, subId: Int) {
        val pdu: GenericPdu = PduParser(pushData).parse() ?: return
        val p = PduPersister.getPduPersister(context)
        val cr = context.contentResolver
        val group = try {
            Transaction.settings?.group
                ?: PreferenceManager.getDefaultSharedPreferences(context).getBoolean("group_message", true)
        } catch (e: Exception) {
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean("group_message", true)
        }

        when (pdu.messageType) {
            PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND -> {
                val nInd = pdu as NotificationInd

                // Some carriers need the transaction id appended to the content location.
                if (MmsConfig.getTransIdEnabled()) {
                    val loc = nInd.contentLocation
                    if (loc != null && loc.isNotEmpty() && loc[loc.size - 1] == '='.code.toByte()) {
                        val tid = nInd.transactionId
                        val merged = ByteArray(loc.size + tid.size)
                        loc.copyInto(merged, 0)
                        tid.copyInto(merged, loc.size)
                        nInd.contentLocation = merged
                    }
                }

                val uri: Uri = p.persist(
                    pdu,
                    Mms.Inbox.CONTENT_URI,
                    !NotificationTransaction.allowAutoDownload(context),
                    group,
                    null,
                    subId
                ) ?: return

                var location = p.getContentLocationFromPduHeader(pdu)
                if (location.isNullOrEmpty()) {
                    val raw = nInd.contentLocation
                    if (raw != null) location = String(raw)
                }
                if (location.isNullOrEmpty()) return

                // Hands off to the library's system-SmsManager download path, which registers its
                // own completion receiver and broadcasts MMS_RECEIVED to the app's MmsReceiver.
                DownloadManager.getInstance()
                    .downloadMultimediaMessage(context, location, uri, true, subId)
            }

            PduHeaders.MESSAGE_TYPE_DELIVERY_IND,
            PduHeaders.MESSAGE_TYPE_READ_ORIG_IND -> {
                val threadId = findThreadId(context, pdu, pdu.messageType)
                if (threadId == -1L) return
                val uri = p.persist(pdu, Mms.Inbox.CONTENT_URI, true, group, null, subId) ?: return
                val values = ContentValues(1)
                values.put(Mms.THREAD_ID, threadId)
                cr.update(uri, values, null, null)
            }
        }
    }

    private fun findThreadId(context: Context, pdu: GenericPdu, type: Int): Long {
        val messageId = if (type == PduHeaders.MESSAGE_TYPE_DELIVERY_IND)
            String((pdu as DeliveryInd).messageId)
        else
            String((pdu as ReadOrigInd).messageId)

        val selection = Mms.MESSAGE_ID + "=" + DatabaseUtils.sqlEscapeString(messageId) +
            " AND " + Mms.MESSAGE_TYPE + "=" + PduHeaders.MESSAGE_TYPE_SEND_REQ
        context.contentResolver.query(
            Mms.CONTENT_URI, arrayOf(Mms.THREAD_ID), selection, null, null
        )?.use { c ->
            if (c.count == 1 && c.moveToFirst()) return c.getLong(0)
        }
        return -1L
    }

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
