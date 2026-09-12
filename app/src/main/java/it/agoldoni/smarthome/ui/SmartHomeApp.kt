package it.agoldoni.smarthome.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.agoldoni.smarthome.ui.devices.ARG_DEVICE_ID
import it.agoldoni.smarthome.ui.devices.DeviceEditScreen
import it.agoldoni.smarthome.ui.devices.DeviceListScreen
import it.agoldoni.smarthome.ui.devices.NEW_DEVICE_ID
import it.agoldoni.smarthome.ui.settings.BrokerSettingsScreen

private object Routes {
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
    const val DEVICE_EDIT = "device/{$ARG_DEVICE_ID}"

    fun deviceEdit(id: Long) = "device/$id"
}

@Composable
fun SmartHomeApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.DEVICES) {
        composable(Routes.DEVICES) {
            DeviceListScreen(
                onAddDevice = { navController.navigate(Routes.deviceEdit(NEW_DEVICE_ID)) },
                onEditDevice = { device -> navController.navigate(Routes.deviceEdit(device.id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.DEVICE_EDIT,
            arguments = listOf(navArgument(ARG_DEVICE_ID) { type = NavType.StringType }),
        ) {
            DeviceEditScreen(onClose = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            BrokerSettingsScreen(onClose = { navController.popBackStack() })
        }
    }
}
