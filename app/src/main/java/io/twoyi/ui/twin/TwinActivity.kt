/* SPDX-License-Identifier: MPL-2.0 */
@file:OptIn(ExperimentalMaterial3Api::class)

package io.twoyi.ui.twin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.twoyi.R

/**
 * Compose shell that mirrors the newest build's two-tab structure: a "Twin"
 * surface and a "Phone" surface for the guest.
 *
 * The shell only establishes theme and navigation. Guest control is still driven
 * by the existing Java path, so nothing here starts or stops a guest yet.
 */
class TwinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TwinShell()
                }
            }
        }
    }
}

private enum class TwinTab(val labelRes: Int) {
    Twin(R.string.twin_tab_twin),
    Phone(R.string.twin_tab_phone)
}

@Composable
private fun TwinShell() {
    var tab by remember { mutableStateOf(TwinTab.Twin) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tab) {
                TwinTab.Twin -> TwinPlaceholder()
                TwinTab.Phone -> PhonePlaceholder()
            }
        }
        NavigationBar {
            TwinTab.values().forEach { candidate ->
                NavigationBarItem(
                    selected = tab == candidate,
                    onClick = { tab = candidate },
                    icon = {},
                    label = { Text(stringResource(candidate.labelRes)) }
                )
            }
        }
    }
}

@Composable
private fun TwinPlaceholder() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.twin_boot_opening), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.twin_boot_step1_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.twin_boot_step1_desc), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PhonePlaceholder() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.twin_phone_empty_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.twin_phone_empty_desc), style = MaterialTheme.typography.bodySmall)
        }
    }
}