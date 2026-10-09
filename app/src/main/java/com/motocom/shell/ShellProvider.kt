package com.motocom.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** No general command endpoint: only an authorized local shell can publish a worker. */
class ShellProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        ShellLink.remember(requireNotNull(context))
        return true
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000 || Binder.getCallingUid() == 0) { "Solo ADB local" }
        require(method == "attach")
        val binder = requireNotNull(extras?.getBinder("display"))
        val token = requireNotNull(arg)
        return Bundle().apply { putBinder("lifetime", ShellLink.attach(binder, token)) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
