package com.gallerysync.app.data.localdb

import android.content.Context

object DbHolder {
    lateinit var database: WhDatabase

    fun create(applicationContext: Context) {
        database = WhDatabase.getInstance(applicationContext)
    }
}
