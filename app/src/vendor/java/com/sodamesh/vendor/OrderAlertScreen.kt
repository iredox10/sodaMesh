package com.sodamesh.vendor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sodamesh.ui.theme.SodaMeshTheme

/**
 * Full-screen incoming-order alert.
 *
 * @param order the pending order to display.
 * @param onAccept accept -> ACK(accepted) via the ViewModel.
 * @param onReject reject -> ACK(rejected) via the ViewModel.
 * @param onAlertFired side-effect hook fired once per [order.orderId]; the host
 *   wires actual sound playback (e.g. MediaPlayer/Ringtone) and vibration here.
 *   Kept as a callback so this screen stays testable and permission-free.
 */
@Composable
fun OrderAlertScreen(
    order: VendorOrder,
    onAccept: (orderId: String) -> Unit,
    onReject: (orderId: String) -> Unit,
    onAlertFired: (orderId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(order.orderId) {
        onAlertFired(order.orderId)
    }

    SodaMeshTheme {
        Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "New order!",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = order.customerName,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    text = "Order ${order.orderId}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Divider()
                order.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${item.qty} × ${item.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatCents(item.lineTotalCents),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Divider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "Total", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = order.totalFormatted,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onReject(order.orderId) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                Text("Reject", modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = { onAccept(order.orderId) },
                modifier = Modifier.weight(1f),
            ) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null)
                Text("Accept", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    }
}

/** Empty state when there is no pending order to show. */
@Composable
fun OrderAlertEmpty(
    modifier: Modifier = Modifier,
) {
    SodaMeshTheme {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No incoming orders",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
