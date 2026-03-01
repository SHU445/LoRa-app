package com.example.applora

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Point d'entrée de l'application.
 * Permet de savoir si l'app est au premier plan (pour n'afficher les notifications
 * de message qu'en arrière-plan).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isInForeground = true
            }
            override fun onStop(owner: LifecycleOwner) {
                isInForeground = false
            }
        })
    }

    companion object {
        @Volatile
        var isInForeground: Boolean = false
            private set
    }
}
