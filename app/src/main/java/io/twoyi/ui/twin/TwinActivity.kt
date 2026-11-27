/* SPDX-License-Identifier: MPL-2.0 */
@file:OptIn(ExperimentalMaterial3Api::class)

package io.twoyi.ui.twin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.twoyi.R

/**
 * Compose shell mirroring the newest build's structure: a two-step onboarding, a
 * Twin chat surface and a Phone surface, plus the model-connection screen.
 *
 * The copy is the newest build's own wording so the product reads the same; the
 * layout and code are written here rather than taken from the decompiled output.
 * Nothing in this file starts or stops a guest yet: the state below is the shell
 * the runtime will drive once the container is wired to it.
 */
class TwinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TwinApp()
                }
            }
        }
    }
}

private enum class Tab(val label: Int) {
    Twin(R.string.twin_tab_twin),
    Phone(R.string.twin_tab_phone),
}

private enum class Screen { Boot, Main, Provider }

private class TwinState {
    var screen by mutableStateOf(Screen.Boot)
    var tab by mutableStateOf(Tab.Twin)
    var phoneCreated by mutableStateOf(false)
    var modelConnected by mutableStateOf(false)
    var draft by mutableStateOf("")
    val messages = mutableStateListOf<Pair<String, Boolean>>()
}

@Composable
private fun TwinApp() {
    val state = remember { TwinState() }
    when (state.screen) {
        Screen.Boot -> BootScreen(state)
        Screen.Main -> MainScreen(state)
        Screen.Provider -> ProviderScreen(state)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun BootScreen(state: TwinState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.twin_boot_before_start),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(R.string.twin_boot_opening),
            style = MaterialTheme.typography.bodyLarge
        )
        BootStep(
            title = if (state.phoneCreated) R.string.twin_boot_step1_done_title else R.string.twin_boot_step1_title,
            desc = if (state.phoneCreated) R.string.twin_boot_step1_done_desc else R.string.twin_boot_step1_desc,
            action = R.string.twin_boot_step1_action,
            done = state.phoneCreated,
            onAction = { state.phoneCreated = true }
        )
        BootStep(
            title = if (state.modelConnected) R.string.twin_boot_step2_done_title else R.string.twin_boot_step2_title,
            desc = if (state.modelConnected) R.string.twin_boot_step2_done_desc else R.string.twin_boot_step2_desc,
            action = R.string.twin_boot_step2_action,
            done = state.modelConnected,
            onAction = { state.screen = Screen.Provider }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                enabled = state.phoneCreated,
                onClick = { state.screen = Screen.Main }
            ) { Text(stringResource(R.string.twin_tab_twin)) }
        }
    }
}

@Composable
private fun BootStep(
    title: Int,
    desc: Int,
    action: Int,
    done: Boolean,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium)
            if (!done) {
                Button(onClick = onAction) { Text(stringResource(action)) }
            }
        }
    }
}

@Composable
private fun MainScreen(state: TwinState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp)
        ) {
            Text(stringResource(R.string.twin_agent_name), style = MaterialTheme.typography.titleLarge)
        }
        Divider()
        Box(modifier = Modifier.weight(1f)) {
            when (state.tab) {
                Tab.Twin -> TwinSurface(state)
                Tab.Phone -> PhoneSurface(state)
            }
        }
        NavigationBar {
            Tab.values().forEach { candidate ->
                NavigationBarItem(
                    selected = state.tab == candidate,
                    onClick = { state.tab = candidate },
                    icon = {},
                    label = { Text(stringResource(candidate.label)) }
                )
            }
        }
    }
}

@Composable
private fun TwinSurface(state: TwinState) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(
                        if (state.modelConnected) R.string.twin_input_hint
                        else R.string.twin_input_hint_need
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages) { (text, fromTwin) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (fromTwin) Arrangement.Start else Arrangement.End
                    ) {
                        Card {
                            Text(
                                text = text,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
        Divider()
        Composer(state)
    }
}

@Composable
private fun Composer(state: TwinState) {
    val notWired = "(" + stringResource(R.string.twin_agent_not_wired) + ")"
    val enabled = state.modelConnected && state.phoneCreated
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            if (state.draft.isEmpty()) {
                Text(
                    text = stringResource(
                        when {
                            !state.phoneCreated -> R.string.twin_agent_no_phone
                            !state.modelConnected -> R.string.twin_input_hint_need
                            else -> R.string.twin_input_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            BasicTextField(
                value = state.draft,
                onValueChange = { state.draft = it },
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            enabled = enabled && state.draft.isNotBlank(),
            onClick = {
                state.messages.add(state.draft to false)
                // Stand-in for the model reply: the runtime will supply it.
                state.messages.add(notWired to true)
                state.draft = ""
            }
        ) { Text(stringResource(R.string.twin_send)) }
    }
}

@Composable
private fun PhoneSurface(state: TwinState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!state.phoneCreated) {
            SectionTitle(stringResource(R.string.twin_phone_empty_title))
            Text(stringResource(R.string.twin_phone_empty_desc), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.twin_phone_empty_desc_em),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.twin_phone_empty_foot),
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { state.phoneCreated = true }) {
                Text(stringResource(R.string.twin_create_phone))
            }
        } else {
            SectionTitle(stringResource(R.string.twin_agent_name))
            Text(
                text = stringResource(R.string.twin_phone_fullscreen),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ProviderScreen(state: TwinState) {
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = { state.screen = if (state.phoneCreated) Screen.Main else Screen.Boot }) {
            Text(stringResource(R.string.twin_back))
        }
        SectionTitle(stringResource(R.string.twin_provider_title))
        Text(stringResource(R.string.twin_provider_desc), style = MaterialTheme.typography.bodyMedium)
        Field(stringResource(R.string.twin_provider_base_url), baseUrl) { baseUrl = it }
        Field(stringResource(R.string.twin_provider_api_key), apiKey) { apiKey = it }
        Field(stringResource(R.string.twin_provider_model_hint), model) { model = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {}) { Text(stringResource(R.string.twin_provider_fetch_models)) }
            Button(
                enabled = apiKey.isNotBlank() && model.isNotBlank(),
                onClick = {
                    state.modelConnected = true
                    state.screen = Screen.Main
                }
            ) { Text(stringResource(R.string.twin_provider_connect)) }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}