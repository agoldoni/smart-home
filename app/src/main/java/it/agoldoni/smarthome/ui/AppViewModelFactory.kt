package it.agoldoni.smarthome.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.agoldoni.smarthome.SmartHomeApplication
import it.agoldoni.smarthome.di.AppContainer
import it.agoldoni.smarthome.ui.devices.DeviceEditViewModel
import it.agoldoni.smarthome.ui.devices.DeviceListViewModel
import it.agoldoni.smarthome.ui.settings.BrokerSettingsViewModel

/** Unico punto in cui i ViewModel incontrano il contenitore delle dipendenze. */
val AppViewModelFactory = viewModelFactory {
    initializer {
        val container = container()
        DeviceListViewModel(
            container.deviceRepository,
            container.driver,
            container.registrySync,
            container.viewPrefsStore,
        )
    }
    initializer {
        val container = container()
        DeviceEditViewModel(createSavedStateHandle(), container.deviceRepository, container.registrySync)
    }
    initializer {
        val container = container()
        BrokerSettingsViewModel(
            container.settingsStore,
            container.driver,
            container.registryStore,
            container.viewPrefsStore,
        )
    }
}

private fun CreationExtras.container(): AppContainer =
    (this[APPLICATION_KEY] as SmartHomeApplication).container
