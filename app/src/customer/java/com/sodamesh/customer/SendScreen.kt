package com.sodamesh.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sodamesh.common.formatCents
import com.sodamesh.mesh.model.Drink
import com.sodamesh.mesh.model.OrderItem
import com.sodamesh.mesh.model.SodaOrder
import com.sodamesh.ui.theme.SodaMeshTheme

/**
 * Send / order-status screen. Stateless and preview-friendly: renders [state]
 * (+ the [order] summary when one was built) and reports every interaction
 * through callbacks — the caller (nav host) owns the [CustomerViewModel] wiring.
 *
 * State mapping:
 * - Idle: review summary, primary "Send order" action.
 * - Scanning/Sending: indeterminate progress, no actions (in-flight).
 * - Relayed: accepted by the mesh, awaiting vendor ACK (still no actions).
 * - Delivered: success + "New order" action.
 * - Failed: error message + "Retry" / back actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    state: SendState = SendState.Idle,
    order: SodaOrder? = null,
    errorMessage: String? = null,
    onSend: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNewOrder: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Send order") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to cart")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state = state, errorMessage = errorMessage)

            if (order != null) {
                OrderSummaryCard(order = order)
            } else if (state == SendState.Idle) {
                Text(
                    "No order built yet — go back and add drinks to your cart.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            when (state) {
                SendState.Idle -> {
                    Button(
                        onClick = onSend,
                        enabled = order != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send order")
                    }
                }
                SendState.Scanning, SendState.Sending, SendState.Relayed -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        statusHint(state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SendState.Delivered -> {
                    Button(onClick = onNewOrder, modifier = Modifier.fillMaxWidth()) {
                        Text("New order")
                    }
                }
                SendState.Failed -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry")
                        }
                        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                            Text("Back to cart")
                        }
                    }
                }
            }
        }
    }
}

private fun statusHint(state: SendState): String = when (state) {
    SendState.Scanning -> "Scanning for nearby vendors…"
    SendState.Sending -> "Sending over the mesh…"
    SendState.Relayed -> "Relayed — waiting for the vendor to confirm…"
    else -> ""
}

@Composable
private fun StatusCard(
    state: SendState,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    val container = when (state) {
        SendState.Delivered -> MaterialTheme.colorScheme.primaryContainer
        SendState.Failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                SendState.Scanning, SendState.Sending, SendState.Relayed ->
                    CircularProgressIndicator()
                SendState.Delivered ->
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                SendState.Failed ->
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                SendState.Idle ->
                    Icon(Icons.Filled.Send, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(statusTitle(state), style = MaterialTheme.typography.titleMedium)
                val subtitle = if (state == SendState.Failed) {
                    errorMessage ?: "Something went wrong."
                } else {
                    statusHint(state).ifBlank { statusSubtitle(state) }
                }
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun statusTitle(state: SendState): String = when (state) {
    SendState.Idle -> "Ready to send"
    SendState.Scanning -> "Scanning"
    SendState.Sending -> "Sending"
    SendState.Relayed -> "Relayed"
    SendState.Delivered -> "Delivered"
    SendState.Failed -> "Send failed"
}

private fun statusSubtitle(state: SendState): String = when (state) {
    SendState.Idle -> "Review your order, then send it to the vendor."
    SendState.Delivered -> "The vendor confirmed your order."
    else -> ""
}

@Composable
private fun OrderSummaryCard(order: SodaOrder, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Order for ${order.customerName}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            order.items.forEach { item ->
                val name = Drink.byId(item.drinkId)?.name ?: item.drinkId
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "$name × ${item.qty}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val unit = Drink.byId(item.drinkId)?.priceCents ?: 0
                    Text(
                        formatCents(unit * item.qty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    formatCents(order.totalCents),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Order ${order.orderId.take(8)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun previewOrder() = SodaOrder(
    orderId = "order-preview-001",
    shopId = "SODA-STORE-01",
    customerName = "Ada",
    items = listOf(OrderItem("cola", 2), OrderItem("grape", 1)),
    totalCents = 2 * 199 + 189,
    ts = 0L,
)

@Preview(showBackground = true)
@Composable
private fun SendScreenIdlePreview() {
    SodaMeshTheme(dynamicColor = false) {
        SendScreen(state = SendState.Idle, order = previewOrder())
    }
}

@Preview(showBackground = true)
@Composable
private fun SendScreenDeliveredPreview() {
    SodaMeshTheme(dynamicColor = false) {
        SendScreen(state = SendState.Delivered, order = previewOrder())
    }
}

@Preview(showBackground = true)
@Composable
private fun SendScreenFailedPreview() {
    SodaMeshTheme(dynamicColor = false) {
        SendScreen(
            state = SendState.Failed,
            order = previewOrder(),
            errorMessage = "Mesh sender not attached — cannot reach the vendor yet.",
        )
    }
}
