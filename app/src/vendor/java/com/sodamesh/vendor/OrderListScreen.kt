package com.sodamesh.vendor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sodamesh.ui.theme.SodaMeshTheme

/**
 * Scrollable list of all received orders with their ACK status.
 *
 * Pending rows expose inline Accept/Reject actions; decided rows show the
 * status chip only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderListScreen(
    orders: List<VendorOrder>,
    onAccept: (orderId: String) -> Unit,
    onReject: (orderId: String) -> Unit,
    onOrderClick: (orderId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SodaMeshTheme {
        Scaffold(
            modifier = modifier,
            topBar = { TopAppBar(title = { Text("Orders (${orders.size})") }) },
        ) { padding ->
        if (orders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Receipt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "No orders yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Start advertising so customers can send orders.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(orders, key = { it.orderId }) { order ->
                    OrderRow(
                        order = order,
                        onAccept = { onAccept(order.orderId) },
                        onReject = { onReject(order.orderId) },
                        onClick = { onOrderClick(order.orderId) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun OrderRow(
    order: VendorOrder,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.customerName,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "#${order.orderId} · ${order.items.sumOf { it.qty }} items",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OrderStatusChip(status = order.status)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = order.totalFormatted,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (order.status == VendorOrderStatus.PENDING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onReject) {
                            Text(
                                "Reject",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(onClick = onAccept) { Text("Accept") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderStatusChip(
    status: VendorOrderStatus,
    modifier: Modifier = Modifier,
) {
    val (label, container, content) = when (status) {
        VendorOrderStatus.PENDING ->
            Triple(
                "Pending",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        VendorOrderStatus.ACCEPTED ->
            Triple(
                "Accepted",
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        VendorOrderStatus.REJECTED ->
            Triple(
                "Rejected",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = container,
            labelColor = content,
        ),
    )
}
