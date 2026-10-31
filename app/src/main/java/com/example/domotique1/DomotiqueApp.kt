package com.example.domotique1

import android.app.Application
import com.example.domotique1.data.VoletsRepository

class DomotiqueApp : Application() {

    val repository: VoletsRepository by lazy { VoletsRepository.get(this) }

    override fun onCreate() {
        super.onCreate()
        // ouverture des deux sessions des le demarrage (les reconnexions se font seules ensuite)
        repository.connect()
    }
}
