package it.agoldoni.smarthome.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.agoldoni.smarthome.R
import it.agoldoni.smarthome.data.settings.RegistrySettings
import it.agoldoni.smarthome.diagnostics.DebugStatus
import it.agoldoni.smarthome.domain.model.ConnectionState
import it.agoldoni.smarthome.domain.registry.NO_REVISION
import it.agoldoni.smarthome.ui.AppViewModelFactory
import it.agoldoni.smarthome.ui.common.FormField
import it.agoldoni.smarthome.ui.common.SectionHeader
import androidx.compose.material3.OutlinedTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrokerSettingsScreen(
    onClose: () -> Unit,
    viewModel: BrokerSettingsViewModel = viewModel(factory = AppViewModelFactory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val debugStatus by viewModel.debugStatus.collectAsStateWithLifecycle()
    val registry by viewModel.registry.collectAsStateWithLifecycle()
    val form = state.form
    val snackbarHostState = remember { SnackbarHostState() }
    var showPassword by remember { mutableStateOf(false) }

    val savedMessage = stringResource(R.string.broker_saved)
    LaunchedEffect(state.saved) {
        if (state.saved) snackbarHostState.showSnackbar(savedMessage)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.broker_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::save) {
                        Icon(Icons.Default.Check, stringResource(R.string.action_save))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConnectionRow(connection, onReconnect = viewModel::reconnect)

            SectionHeader(stringResource(R.string.broker_title))

            FormField(
                value = form.host,
                onValueChange = { value -> viewModel.edit { it.copy(host = value) } },
                label = stringResource(R.string.field_host),
                helper = "192.168.1.10 oppure broker.casa.lan",
                error = state.hostError,
            )
            FormField(
                value = form.portText,
                onValueChange = { value -> viewModel.edit { it.copy(portText = value.filter(Char::isDigit)) } },
                label = stringResource(R.string.field_port),
                error = state.portError,
                keyboardType = KeyboardType.Number,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.field_tls),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = form.useTls, onCheckedChange = viewModel::setTls)
            }

            FormField(
                value = form.username,
                onValueChange = { value -> viewModel.edit { it.copy(username = value) } },
                label = stringResource(R.string.field_username),
            )

            OutlinedTextField(
                value = form.password,
                onValueChange = { value -> viewModel.edit { it.copy(password = value) } },
                label = { Text(stringResource(R.string.field_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                supportingText = { Text(stringResource(R.string.helper_password)) },
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            stringResource(
                                if (showPassword) R.string.password_hide else R.string.password_show,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            FormField(
                value = form.clientId,
                onValueChange = { value -> viewModel.edit { it.copy(clientId = value) } },
                label = stringResource(R.string.field_client_id),
                helper = stringResource(R.string.helper_client_id),
            )

            RegistrySection(
                registry = registry,
                onToggle = viewModel::setFollowRegistry,
                onPrefixChange = viewModel::setRegistryPrefix,
            )

            DebugApiSection(debugStatus, onToggle = viewModel::setDebugApi)

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Il registro condiviso: se lo si segue, da dove, e a che punto e'.
 *
 * Il prefisso e' qui e non fra le impostazioni del broker perche' non serve a
 * raggiungerlo: serve a dire quale casa si sta guardando. Cambiandolo si passa
 * a un'altra istanza — stesso broker, altri dispositivi, altro registro.
 */
@Composable
private fun RegistrySection(
    registry: RegistrySettings,
    onToggle: (Boolean) -> Unit,
    onPrefixChange: (String) -> Unit,
) {
    SectionHeader(stringResource(R.string.registry_title))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.registry_switch),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = registry.follow, onCheckedChange = onToggle)
    }

    Text(
        text = when {
            !registry.follow -> stringResource(R.string.registry_off)
            registry.appliedRevision == NO_REVISION ->
                stringResource(R.string.registry_waiting, registry.topic)

            else -> stringResource(
                R.string.registry_following,
                registry.appliedRevision,
                formatInstant(registry.receivedAt),
            )
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (registry.follow) {
        Spacer(Modifier.height(8.dp))
        FormField(
            value = registry.prefix,
            onValueChange = onPrefixChange,
            label = stringResource(R.string.field_registry_prefix),
            helper = stringResource(R.string.helper_registry_prefix),
        )
    }
}

/** Ora e giorno, per la riga del registro. */
private fun formatInstant(millis: Long): String =
    if (millis <= 0L) {
        "-"
    } else {
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
    }

/**
 * L'interruttore dell'API di debug, che parte da spento a ogni installazione e
 * c'e in ogni build, release compresa.
 *
 * Sotto c'e l'indirizzo da aprire dal PC: e la cosa che serve davvero, perche
 * quello del telefono lo decide il DHCP e cambia senza avvisare.
 */
@Composable
private fun DebugApiSection(status: DebugStatus, onToggle: (Boolean) -> Unit) {
    val error = status.error
    val endpoint = status.endpoint

    SectionHeader(stringResource(R.string.debug_title))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.debug_switch),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = status.enabled, onCheckedChange = onToggle)
    }

    Text(
        text = when {
            error != null -> stringResource(R.string.debug_error, error)
            !status.enabled -> stringResource(R.string.debug_off)
            !status.running -> stringResource(R.string.debug_starting)
            endpoint != null -> stringResource(R.string.debug_on, endpoint)
            else -> stringResource(R.string.debug_on_address_missing)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun ConnectionRow(state: ConnectionState, onReconnect: () -> Unit) {
    val message = when (state) {
        ConnectionState.NotConfigured -> stringResource(R.string.connection_not_configured)
        ConnectionState.Connecting -> stringResource(R.string.connection_connecting)
        ConnectionState.Connected -> stringResource(R.string.connection_connected)
        ConnectionState.Disconnected -> stringResource(R.string.connection_disconnected)
        is ConnectionState.Failed -> state.reason
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is ConnectionState.Connecting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is ConnectionState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onReconnect) {
            Text(stringResource(R.string.action_reconnect))
        }
    }
}
