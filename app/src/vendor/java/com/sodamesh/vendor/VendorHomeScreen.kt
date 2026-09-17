package com.sodamesh.vendor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sodamesh.ui.theme.SodaMeshTheme

/**
 * State-hoisted vendor home: store advertise toggle + order count badge.
 *
 * @param isAdvertising whether BLE advertising (store open) is on.
 * @param orderCount total orders received this session.
 * @param pendingCount orders still awaiting accept/reject; shown as the badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VendorHomeScreen(
    isAdvertising: Boolean,
    orderCount: Int,
    pendingCount: Int,
    onToggleAdvertise: () -> Unit,
    onViewOrders: () -> Unit,
    onViewAlerts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // SodaMeshTheme applied here so the vendor flavor screens carry the app
    // theme even when hosted outside the main app scaffold.
    SodaMeshTheme {
        Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Store") },
                actions = {
                    BadgedBox(
                        badge = {
                            if (pendingCount > 0) Badge { Text(pendingCount.toString()) }
                        },
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = "Pending orders",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAdvertising) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Store,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = if (isAdvertising) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Column {
                            Text(
                                text = if (isAdvertising) "Store open" else "Store closed",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = if (isAdvertising) {
                                    "Advertising — customers can find you"
                                } else {
                                    "Not advertising"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Switch(
                        checked = isAdvertising,
                        onCheckedChange = { onToggleAdvertise() },
                    )
                }
            }

            Button(
                onClick = onToggleAdvertise,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isAdvertising) "Stop advertising" else "Start store advertise")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onViewOrders,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Orders ($orderCount)")
                }
                OutlinedButton(
                    onClick = onViewAlerts,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Alerts ($pendingCount)")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/** ViewModel-bound route. Caller passes the Hilt-injected [VendorViewModel]. */
@Composable
fun VendorHomeRoute(
    viewModel: VendorViewModel,
    onViewOrders: () -> Unit,
    onViewAlerts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAdvertising by viewModel.isAdvertising.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    VendorHomeScreen(
        isAdvertising = isAdvertising,
        orderCount = orders.size,
        pendingCount = pendingCount,
        onToggleAdvertise = viewModel::toggleAdvertising,
        onViewOrders = onViewOrders,
        onViewAlerts = onViewAlerts,
        modifier = modifier,
    )
}
