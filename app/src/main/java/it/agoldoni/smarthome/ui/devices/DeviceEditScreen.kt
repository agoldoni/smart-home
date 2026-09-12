package it.agoldoni.smarthome.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.agoldoni.smarthome.R
import it.agoldoni.smarthome.domain.model.DeviceKind
import it.agoldoni.smarthome.ui.AppViewModelFactory
import it.agoldoni.smarthome.ui.common.FormField
import it.agoldoni.smarthome.ui.common.SectionHeader
import it.agoldoni.smarthome.ui.common.iconRes
import it.agoldoni.smarthome.ui.common.labelRes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceEditScreen(
    onClose: () -> Unit,
    viewModel: DeviceEditViewModel = viewModel(factory = AppViewModelFactory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val form = state.form
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.closed) {
        if (state.closed) onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.device_new_title else R.string.device_edit_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Seguendo il registro non c'e' niente da salvare e niente
                    // da cancellare: i due comandi spariscono invece di
                    // restare li' a non fare niente.
                    if (!state.readOnly) {
                        if (!state.isNew) {
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.action_delete))
                            }
                        }
                        IconButton(onClick = viewModel::save) {
                            Icon(Icons.Default.Check, stringResource(R.string.action_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.readOnly) {
                Text(
                    text = stringResource(R.string.registry_readonly),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            SectionHeader(stringResource(R.string.section_identity))

            FormField(
                value = form.name,
                onValueChange = { value -> viewModel.edit { it.copy(name = value) } },
                label = stringResource(R.string.field_name),
                error = state.nameError,
                enabled = !state.readOnly,
            )
            FormField(
                value = form.room,
                onValueChange = { value -> viewModel.edit { it.copy(room = value) } },
                label = stringResource(R.string.field_room),
                enabled = !state.readOnly,
            )

            Text(
                text = stringResource(R.string.field_kind),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceKind.entries.forEach { kind ->
                    FilterChip(
                        selected = form.kind == kind,
                        enabled = !state.readOnly,
                        onClick = { viewModel.edit { it.copy(kind = kind) } },
                        label = { Text(stringResource(kind.labelRes)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(kind.iconRes),
                                contentDescription = null,
                            )
                        },
                    )
                }
            }

            SectionHeader(stringResource(R.string.section_state))

            FormField(
                value = form.stateTopic,
                onValueChange = { value -> viewModel.edit { it.copy(stateTopic = value) } },
                label = stringResource(R.string.field_state_topic),
                helper = stringResource(R.string.helper_state_topic),
                error = state.stateTopicError,
                enabled = !state.readOnly,
            )
            FormField(
                value = form.stateJsonKey,
                onValueChange = { value -> viewModel.edit { it.copy(stateJsonKey = value) } },
                label = stringResource(R.string.field_state_json_key),
                helper = stringResource(R.string.helper_state_json_key),
                enabled = !state.readOnly,
            )
            FormField(
                value = form.powerJsonKey,
                onValueChange = { value -> viewModel.edit { it.copy(powerJsonKey = value) } },
                label = stringResource(R.string.field_power_json_key),
                helper = stringResource(R.string.helper_power_json_key),
                enabled = !state.readOnly,
            )

            SectionHeader(stringResource(R.string.section_energy))

            FormField(
                value = form.energyTopic,
                onValueChange = { value -> viewModel.edit { it.copy(energyTopic = value) } },
                label = stringResource(R.string.field_energy_topic),
                helper = stringResource(R.string.helper_energy_topic),
                enabled = !state.readOnly,
            )
            // Le due chiavi servono solo a chi ha un topic dei consumi: senza,
            // sarebbero due caselle che non governano niente.
            if (form.energyTopic.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = form.energyTodayJsonKey,
                        onValueChange = { value -> viewModel.edit { it.copy(energyTodayJsonKey = value) } },
                        label = stringResource(R.string.field_energy_today_json_key),
                        error = state.energyTodayJsonKeyError,
                        modifier = Modifier.weight(1f),
                        enabled = !state.readOnly,
                    )
                    FormField(
                        value = form.energyMonthJsonKey,
                        onValueChange = { value -> viewModel.edit { it.copy(energyMonthJsonKey = value) } },
                        label = stringResource(R.string.field_energy_month_json_key),
                        modifier = Modifier.weight(1f),
                        enabled = !state.readOnly,
                    )
                }
            }

            SectionHeader(stringResource(R.string.section_availability))

            FormField(
                value = form.availabilityTopic,
                onValueChange = { value -> viewModel.edit { it.copy(availabilityTopic = value) } },
                label = stringResource(R.string.field_availability_topic),
                helper = stringResource(R.string.helper_availability_topic),
                enabled = !state.readOnly,
            )
            // I due payload servono solo a chi ha un topic di disponibilita: senza,
            // sarebbero due caselle che non governano niente.
            if (form.availabilityTopic.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = form.payloadAvailable,
                        onValueChange = { value -> viewModel.edit { it.copy(payloadAvailable = value) } },
                        label = stringResource(R.string.field_payload_available),
                        modifier = Modifier.weight(1f),
                        enabled = !state.readOnly,
                    )
                    FormField(
                        value = form.payloadUnavailable,
                        onValueChange = { value -> viewModel.edit { it.copy(payloadUnavailable = value) } },
                        label = stringResource(R.string.field_payload_unavailable),
                        modifier = Modifier.weight(1f),
                        enabled = !state.readOnly,
                    )
                }
            }

            if (form.kind != DeviceKind.SENSOR) {
                SectionHeader(stringResource(R.string.section_command))

                FormField(
                    value = form.commandTopic,
                    onValueChange = { value -> viewModel.edit { it.copy(commandTopic = value) } },
                    label = stringResource(R.string.field_command_topic),
                    helper = stringResource(R.string.helper_command_topic),
                    error = state.commandTopicError,
                    enabled = !state.readOnly,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField(
                        value = form.payloadOn,
                        onValueChange = { value -> viewModel.edit { it.copy(payloadOn = value) } },
                        label = stringResource(R.string.field_payload_on),
                        modifier = Modifier.weight(1f),
                        enabled = !state.readOnly,
                    )
                    FormField(
                        value = form.payloadOff,
                        onValueChange = { value -> viewModel.edit { it.copy(payloadOff = value) } },
                        label = stringResource(R.string.field_payload_off),
                        modifier = Modifier.weight(1f),
                        enabled = !state.readOnly,
                    )
                }
            }

            if (form.kind == DeviceKind.DIMMER) {
                SectionHeader(stringResource(R.string.section_level))

                FormField(
                    value = form.levelCommandTopic,
                    onValueChange = { value -> viewModel.edit { it.copy(levelCommandTopic = value) } },
                    label = stringResource(R.string.field_level_command_topic),
                    error = state.levelCommandTopicError,
                    enabled = !state.readOnly,
                )
                FormField(
                    value = form.levelStateTopic,
                    onValueChange = { value -> viewModel.edit { it.copy(levelStateTopic = value) } },
                    label = stringResource(R.string.field_level_state_topic),
                    helper = stringResource(R.string.helper_level_state_topic),
                    enabled = !state.readOnly,
                )
                FormField(
                    value = form.levelJsonKey,
                    onValueChange = { value -> viewModel.edit { it.copy(levelJsonKey = value) } },
                    label = stringResource(R.string.field_level_json_key),
                    enabled = !state.readOnly,
                )
                FormField(
                    value = form.levelMaxText,
                    onValueChange = { value ->
                        viewModel.edit { it.copy(levelMaxText = value.filter(Char::isDigit)) }
                    },
                    label = stringResource(R.string.field_level_max),
                    helper = stringResource(R.string.helper_level_max),
                    keyboardType = KeyboardType.Number,
                    enabled = !state.readOnly,
                )
            }

            if (form.kind != DeviceKind.SENSOR) {
                SectionHeader(stringResource(R.string.section_advanced))

                Text(
                    text = stringResource(R.string.field_qos),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..2).forEach { level ->
                        FilterChip(
                            selected = form.qos == level,
                            enabled = !state.readOnly,
                            onClick = { viewModel.edit { it.copy(qos = level) } },
                            label = { Text(level.toString()) },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.field_retained),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.helper_retained),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = form.retained,
                        enabled = !state.readOnly,
                        onCheckedChange = { value -> viewModel.edit { it.copy(retained = value) } },
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_dialog_title, form.name)) },
            text = { Text(stringResource(R.string.delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
