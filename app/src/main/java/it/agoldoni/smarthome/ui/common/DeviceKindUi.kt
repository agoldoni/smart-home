package it.agoldoni.smarthome.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import it.agoldoni.smarthome.R
import it.agoldoni.smarthome.domain.model.DeviceKind

@get:DrawableRes
val DeviceKind.iconRes: Int
    get() = when (this) {
        DeviceKind.SWITCH -> R.drawable.ic_device_switch
        DeviceKind.LIGHT -> R.drawable.ic_device_light
        DeviceKind.DIMMER -> R.drawable.ic_device_dimmer
        DeviceKind.SENSOR -> R.drawable.ic_device_sensor
    }

@get:StringRes
val DeviceKind.labelRes: Int
    get() = when (this) {
        DeviceKind.SWITCH -> R.string.device_kind_switch
        DeviceKind.LIGHT -> R.string.device_kind_light
        DeviceKind.DIMMER -> R.string.device_kind_dimmer
        DeviceKind.SENSOR -> R.string.device_kind_sensor
    }
