package com.xiaoling.core

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** 通讯录:姓名 → 号码(需 READ_CONTACTS 权限) */
object Contacts {
    fun lookup(ctx: Context, name: String): String? {
        if (name.isBlank()) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
        )
        val selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
        val escaped = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val args = arrayOf("%$escaped%")
        return try {
            ctx.contentResolver.query(uri, projection, selection, args, null)?.use { cur ->
                var bestNumber: String? = null
                var bestScore = Int.MIN_VALUE
                while (cur.moveToNext()) {
                    val display = cur.getString(0).orEmpty()
                    val number = cur.getString(1).orEmpty().replace(Regex("[\\s-]"), "")
                    val primary = cur.getInt(2) == 1
                    val score = when {
                        display == name -> 100
                        display.startsWith(name) -> 70
                        display.contains(name) -> 50
                        else -> 0
                    } + if (primary) 10 else 0
                    if (number.isNotBlank() && score > bestScore) {
                        bestScore = score
                        bestNumber = number
                    }
                }
                bestNumber
            }
        } catch (e: Exception) {
            null
        }
    }
}
