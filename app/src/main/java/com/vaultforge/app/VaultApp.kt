package com.vaultforge.app

import android.app.Application
import com.vaultforge.app.data.VaultStore
import com.vaultforge.app.server.VaultServer

class VaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        store = VaultStore(this)
        store.load()
        VaultServer.start(store)
    }

    companion object {
        lateinit var store: VaultStore
    }
}