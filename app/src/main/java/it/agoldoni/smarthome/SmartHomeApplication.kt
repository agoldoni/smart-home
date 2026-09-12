package it.agoldoni.smarthome

import android.app.Application
import it.agoldoni.smarthome.di.AppContainer

class SmartHomeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
