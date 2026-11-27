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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.twoyi.R
import io.twoyi.TwinProviderRules

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

private enum class Screen { Boot, Main, Provider, Settings, Memory }

/**
 * A line in the Twin conversation. `Need` is the confirmation turn: the Twin asked
 * something that has a consequence, and waits for the user before continuing.
 */
private sealed class Line {
    class User(val text: String) : Line()
    class Twin(val text: String) : Line()
    class Need(val template: Int, val arg: String, val consequence: Int?) : Line() {
        var resolved by mutableStateOf(false)
        var outcome by mutableStateOf<Int?>(null)
    }
}

private class TwinState(
    val phone: TwinRuntime.PhoneState,
    val modelConnected: Boolean
) {
    var screen by mutableStateOf(Screen.Boot)
    var tab by mutableStateOf(Tab.Twin)
    var phoneCreated by mutableStateOf(phone.created)
    var phoneReady by mutableStateOf(phone.ready)
    var busy by mutableStateOf(false)
    var error by mutableStateOf(phone.message)
    var model by mutableStateOf(modelConnected)
    var draft by mutableStateOf("")
    val messages = mutableStateListOf<Line>()
}

@Composable
private fun TwinApp() {
    val context = LocalContext.current
    val state = remember {
        val phone = TwinRuntime.phoneState(context)
        TwinState(phone, TwinRuntime.isProviderConnected(context))
    }
    when (state.screen) {
        Screen.Boot -> BootScreen(state)
        Screen.Main -> MainScreen(state)
        Screen.Provider -> ProviderScreen(state)
        Screen.Settings -> SettingsScreen(state)
        Screen.Memory -> MemoryScreen(state)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun BootScreen(state: TwinState) {
    val context = LocalContext.current
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
            busy = state.busy,
            onAction = {
                state.busy = true
                Thread {
                    val result = TwinRuntime.createPhone(context)
                    state.busy = false
                    state.phoneCreated = result.created
                    state.phoneReady = result.ready
                    state.error = result.message
                }.start()
            }
        )
        BootStep(
            title = if (state.model) R.string.twin_boot_step2_done_title else R.string.twin_boot_step2_title,
            desc = if (state.model) R.string.twin_boot_step2_done_desc else R.string.twin_boot_step2_desc,
            action = R.string.twin_boot_step2_action,
            done = state.model,
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
    busy: Boolean = false,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium)
            if (!done) {
                Button(enabled = !busy, onClick = onAction) {
                    Text(
                        stringResource(
                            if (busy) R.string.twin_boot_preparing else action
                        )
                    )
                }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.twin_agent_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { state.screen = Screen.Settings }) {
                    Text(stringResource(R.string.twin_settings_title))
                }
            }
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
                        if (state.model) R.string.twin_input_hint
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
                items(state.messages) { line ->
                    when (line) {
                        is Line.User -> Bubble(line.text, fromTwin = false)
                        is Line.Twin -> Bubble(line.text, fromTwin = true)
                        is Line.Need -> NeedCard(line)
                    }
                }
            }
        }
        val waiting = state.messages.any { it is Line.Need && !it.resolved }
        if (waiting) {
            Text(
                text = stringResource(R.string.twin_need_waiting),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Divider()
        Composer(state)
    }
}

@Composable
private fun Bubble(text: String, fromTwin: Boolean) {
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

/**
 * The confirmation turn. The Twin states what it wants to do and the consequence,
 * then offers accept / reject / hand-over. Every state has its own copy, matching
 * the newest build's wording.
 */
@Composable
private fun NeedCard(line: Line.Need) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(line.template, line.arg),
                    style = MaterialTheme.typography.bodyMedium
                )
                line.consequence?.let { consequence ->
                    Text(
                        text = stringResource(consequence),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!line.resolved) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            line.resolved = true
                            line.outcome = R.string.twin_need_row_accepted
                        }) { Text(stringResource(R.string.twin_need_accept)) }
                        OutlinedButton(onClick = {
                            line.resolved = true
                            line.outcome = R.string.twin_need_row_declined
                        }) { Text(stringResource(R.string.twin_need_reject)) }
                    }
                    TextButton(onClick = {
                        // Hand the turn to the user: open the phone, then mark it handled.
                        TwinRuntime.startPhone(context)
                        line.resolved = true
                        line.outcome = R.string.twin_need_row_handled
                    }) { Text(stringResource(R.string.twin_need_go_phone)) }
                } else {
                    Text(
                        text = stringResource(line.outcome ?: R.string.twin_need_row_handled),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(state: TwinState) {
    val context = LocalContext.current
    val notWired = "(" + stringResource(R.string.twin_agent_not_wired) + ")"
    val enabled = state.model && state.phoneCreated
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
                            !state.model -> R.string.twin_input_hint_need
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
                val text = state.draft.trim()
                state.messages.add(Line.User(text))
                state.draft = ""
                val consequence = riskyConsequence(text)
                if (consequence != null && TwinRuntime.isFlag(context, TwinRuntime.FLAG_CONFIRM_RISKY)) {
                    // Placeholder turn: the real model will produce this question. The
                    // shell only demonstrates the confirmation interaction end to end.
                    state.messages.add(
                        Line.Need(
                            template = R.string.twin_need_tap_question,
                            arg = text,
                            consequence = consequence
                        )
                    )
                } else {
                    state.messages.add(Line.Twin(notWired))
                }
            }
        ) { Text(stringResource(R.string.twin_send)) }
    }
}

/**
 * Which consequence a draft implies, or null when it is an ordinary request.
 *
 * This is deliberately conservative and local: it only decides which confirmation
 * copy to show. The model decides what actually needs confirming once it is wired.
 */
private fun riskyConsequence(text: String): Int? {
    val lower = text.lowercase()
    return when {
        lower.contains("delete") || lower.contains("remove") || lower.contains("删除") ->
            R.string.twin_need_consequence_delete
        lower.contains("pay") || lower.contains("buy") || lower.contains("付款") || lower.contains("支付") ->
            R.string.twin_need_consequence_pay
        lower.contains("send") || lower.contains("post") || lower.contains("发送") || lower.contains("发布") ->
            R.string.twin_need_consequence_send
        else -> null
    }
}

@Composable
private fun PhoneSurface(state: TwinState) {
    val context = LocalContext.current
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
            Button(
                enabled = !state.busy,
                onClick = {
                    state.busy = true
                    Thread {
                        val result = TwinRuntime.createPhone(context)
                        state.busy = false
                        state.phoneCreated = result.created
                        state.phoneReady = result.ready
                        state.error = result.message
                    }.start()
                }
            ) {
                Text(
                    stringResource(
                        if (state.busy) R.string.twin_boot_preparing else R.string.twin_create_phone
                    )
                )
            }
        } else {
            SectionTitle(stringResource(R.string.twin_agent_name))
            Button(
                enabled = state.phoneReady && !state.busy,
                onClick = {
                    state.error = TwinRuntime.startPhone(context)
                }
            ) { Text(stringResource(R.string.twin_phone_fullscreen)) }
            if (!state.phoneReady) {
                Text(
                    text = stringResource(R.string.twin_boot_preparing),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        val reason = state.error
        if (reason != null) {
            Text(text = reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProviderScreen(state: TwinState) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf(listOf<Int>()) }
    var models by remember { mutableStateOf(listOf<String>()) }
    var fetching by remember { mutableStateOf(false) }
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
        problems.forEach { problem ->
            Text(
                text = stringResource(problem),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        models.forEach { candidate ->
            OutlinedButton(
                onClick = { model = candidate },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (candidate == model) "\u2713 $candidate" else candidate,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !fetching,
                onClick = {
                    val keyProblem = TwinProviderRules.checkKey(apiKey)
                    val urlProblem = TwinProviderRules.checkBaseUrl(baseUrl)
                    if (keyProblem != TwinProviderRules.KeyProblem.NONE || urlProblem != TwinProviderRules.UrlProblem.NONE) {
                        val found = mutableListOf<Int>()
                        if (keyProblem == TwinProviderRules.KeyProblem.REQUIRED) {
                            found.add(R.string.twin_api_key_required)
                        }
                        if (keyProblem == TwinProviderRules.KeyProblem.BAD_CHARS) {
                            found.add(R.string.twin_api_key_bad_chars)
                        }
                        if (urlProblem == TwinProviderRules.UrlProblem.NOT_HTTP ||
                            urlProblem == TwinProviderRules.UrlProblem.MALFORMED
                        ) {
                            found.add(R.string.twin_base_url_invalid)
                        }
                        problems = found
                    } else {
                        fetching = true
                        problems = emptyList()
                        Thread {
                            val result = TwinModelService.fetch(context, baseUrl, apiKey)
                            fetching = false
                            when (result) {
                                is TwinModelService.Result.Ok -> models = result.models
                                is TwinModelService.Result.Failed -> problems = listOf(result.messageRes)
                            }
                        }.start()
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (fetching) R.string.twin_provider_fetching_models
                        else R.string.twin_provider_fetch_models
                    )
                )
            }
            Button(
                onClick = {
                    val found = mutableListOf<Int>()
                    when (TwinProviderRules.checkKey(apiKey)) {
                        TwinProviderRules.KeyProblem.REQUIRED ->
                            found.add(R.string.twin_api_key_required)
                        TwinProviderRules.KeyProblem.BAD_CHARS ->
                            found.add(R.string.twin_api_key_bad_chars)
                        TwinProviderRules.KeyProblem.NONE -> Unit
                    }
                    when (TwinProviderRules.checkBaseUrl(baseUrl)) {
                        TwinProviderRules.UrlProblem.NOT_HTTP,
                        TwinProviderRules.UrlProblem.MALFORMED ->
                            found.add(R.string.twin_base_url_invalid)
                        else -> Unit
                    }
                    if (!TwinProviderRules.isModelNameValid(model)) {
                        found.add(R.string.twin_provider_model_hint)
                    }
                    problems = found
                    if (found.isEmpty()) {
                        val saved = TwinRuntime.rememberProvider(context, baseUrl.trim(), model, apiKey)
                        if (!saved) {
                            problems = listOf(R.string.twin_key_save_failed)
                        } else {
                            state.model = true
                            state.screen = Screen.Main
                        }
                    }
                }
            ) { Text(stringResource(R.string.twin_provider_connect)) }
        }
    }
}

@Composable
private fun SettingsScreen(state: TwinState) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = { state.screen = Screen.Main }) {
            Text(stringResource(R.string.twin_back))
        }
        SectionTitle(stringResource(R.string.twin_settings_title))
        GroupHeader(R.string.twin_settings_group_twin)
        InfoRow(
            R.string.twin_settings_model,
            if (state.model) {
                val label = TwinRuntime.lastProviderLabel(context)
                    ?: stringResource(R.string.twin_provider_active)
                val url = TwinRuntime.lastProviderUrl(context)
                if (url.isNullOrEmpty()) label else "$label\n$url"
            } else {
                stringResource(R.string.twin_settings_model_disconnected)
            }
        )
        ToggleRow(
            R.string.twin_settings_see_screen,
            R.string.twin_settings_see_screen_desc,
            TwinRuntime.FLAG_SEE_SCREEN
        )
        ToggleRow(
            R.string.twin_settings_sensitive_guard,
            R.string.twin_settings_sensitive_guard_desc,
            TwinRuntime.FLAG_SENSITIVE_GUARD
        )
        ToggleRow(
            R.string.twin_settings_confirm_risky,
            R.string.twin_settings_confirm_risky_desc,
            TwinRuntime.FLAG_CONFIRM_RISKY
        )
        TextButton(onClick = { state.screen = Screen.Memory }) {
            Text(stringResource(R.string.twin_settings_memory))
        }
        GroupHeader(R.string.twin_settings_group_phone)
        InfoRow(R.string.twin_settings_phone_mode, stringResource(R.string.twin_settings_phone_twin))
        if (!state.phoneCreated) {
            HintRow(R.string.twin_settings_needs_phone)
        } else if (!state.phoneReady) {
            HintRow(R.string.twin_settings_needs_boot)
        } else {
            InfoRow(R.string.twin_settings_files, stringResource(R.string.twin_settings_files_desc))
        }
        GroupHeader(R.string.twin_settings_group_host)
        HintRow(R.string.twin_settings_host_battery_desc)
        GroupHeader(R.string.twin_settings_group_help)
        HintRow(R.string.twin_settings_send_log_desc)
    }
}

@Composable
private fun MemoryScreen(state: TwinState) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(TwinRuntime.memories(context)) }
    var confirming by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = { state.screen = Screen.Settings }) {
            Text(stringResource(R.string.twin_back))
        }
        SectionTitle(stringResource(R.string.twin_memory_title))
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.twin_memory_empty),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            entries.forEach { note ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(note, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        TwinRuntime.forget(context, note)
                        entries = TwinRuntime.memories(context)
                    }) { Text(stringResource(R.string.twin_memory_delete)) }
                }
            }
            if (confirming) {
                Text(
                    text = stringResource(R.string.twin_memory_clear_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        TwinRuntime.forgetAll(context)
                        entries = TwinRuntime.memories(context)
                        confirming = false
                    }) { Text(stringResource(R.string.twin_memory_clear)) }
                    OutlinedButton(onClick = { confirming = false }) {
                        Text(stringResource(R.string.twin_memory_keep))
                    }
                }
            } else {
                TextButton(onClick = { confirming = true }) {
                    Text(stringResource(R.string.twin_memory_clear))
                }
            }
        }
        Text(
            text = stringResource(R.string.twin_memory_note),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun GroupHeader(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun InfoRow(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HintRow(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ToggleRow(title: Int, desc: Int, flag: String) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(TwinRuntime.isFlag(context, flag)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                val stateLabel = if (checked) {
                    stringResource(R.string.twin_provider_active)
                } else {
                    stringResource(R.string.twin_settings_pip_off)
                }
                TextButton(onClick = {
                    checked = !checked
                    TwinRuntime.setFlag(context, flag, checked)
                }) { Text(stateLabel) }
            }
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall)
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