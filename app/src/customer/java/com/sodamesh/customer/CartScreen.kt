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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sodamesh.common.Cart
import com.sodamesh.common.formatCents
import com.sodamesh.mesh.model.Drink
import com.sodamesh.ui.theme.SodaMeshTheme

/**
 * Cart review. Stateless and preview-friendly: joins [cart] against [menu]
 * internally and reports every interaction through callbacks — the caller
 * (nav host) owns the [CustomerViewModel] wiring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    cart: Map<String, Int> = emptyMap(),
    menu: List<Drink> = Drink.MENU,
    customerName: String = "",
    onNameChange: (String) -> Unit = {},
    onSetQty: (drinkId: String, qty: Int) -> Unit = { _, _ -> },
    onClear: () -> Unit = {},
    onCheckout: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val lines = cart.mapNotNull { (drinkId, qty) ->
        val drink = menu.firstOrNull { it.id == drinkId } ?: return@mapNotNull null
        drink to qty
    }
    val total = Cart.totalCents(cart, menu)
    val canCheckout = lines.isNotEmpty()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Your cart") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back to menu")
                    }
                },
                actions = {
                    if (lines.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear cart")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Add some sodas from the menu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) { Text("Back to menu") }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = customerName,
                    onValueChange = onNameChange,
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(lines, key = { (drink, _) -> drink.id }) { (drink, qty) ->
                        CartLineRow(
                            drink = drink,
                            qty = qty,
                            onSetQty = { onSetQty(drink.id, it) },
                        )
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Total", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatCents(total),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCheckout,
                    enabled = canCheckout,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Send order")
                }
            }
        }
    }
}

@Composable
private fun CartLineRow(
    drink: Drink,
    qty: Int,
    onSetQty: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(drink.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatCents(drink.priceCents)} each • ${formatCents(drink.priceCents * qty)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onSetQty(qty - 1) }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Decrease ${drink.name}")
                }
                Text("$qty", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { onSetQty(qty + 1) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Increase ${drink.name}")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CartScreenPreview() {
    SodaMeshTheme(dynamicColor = false) {
        CartScreen(
            cart = mapOf("cola" to 2, "grape" to 1),
            customerName = "Ada",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CartScreenEmptyPreview() {
    SodaMeshTheme(dynamicColor = false) {
        CartScreen()
    }
}
