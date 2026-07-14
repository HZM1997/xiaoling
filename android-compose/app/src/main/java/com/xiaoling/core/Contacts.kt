package com.xiaoling.core

import android.content.Context
import android.provider.ContactsContract

/** 通讯录:姓名 → 号码(需 READ_CONTACTS 权限) */
object Contacts {
    fun lookup(ctx: Context, name: String): String? {
        if (name.isBlank()) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
        val args = arrayOf("%$name%")
        return try {
            ctx.contentResolver.query(uri, projection, selection, args, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0)?.replace(" ", "")?.replace("-", "") else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
