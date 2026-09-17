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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * Menu (customer home). Stateless and preview-friendly: renders [menu] + [cart]
 * and reports every interaction through callbacks — the caller (nav host) owns
 * the [CustomerViewModel] wiring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    menu: List<Drink> = Drink.MENU,
    cart: Map<String, Int> = emptyMap(),
    onAdd: (drinkId: String) -> Unit = {},
    onRemoveOne: (drinkId: String) -> Unit = {},
    onOpenCart: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val count = Cart.count(cart)
    val total = Cart.totalCents(cart, menu)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("SodaMesh") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (count > 0) Text("$count", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = onOpenCart) {
                            Icon(Icons.Filled.ShoppingCart, contentDescription = "Open cart")
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Text(
                    text = if (count > 0) "${formatCents(total)} • $count item${if (count == 1) "" else "s"}" else "Cart is empty",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onOpenCart, enabled = count > 0) {
                    Text("View cart")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(menu, key = { it.id }) { drink ->
                DrinkRow(
                    drink = drink,
                    qty = cart[drink.id] ?: 0,
                    onAdd = { onAdd(drink.id) },
                    onRemoveOne = { onRemoveOne(drink.id) },
                )
            }
        }
    }
}

@Composable
private fun DrinkRow(
    drink: Drink,
    qty: Int,
    onAdd: () -> Unit,
    onRemoveOne: () -> Unit,
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
                    formatCents(drink.priceCents),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (qty == 0) {
                FilledTonalButton(onClick = onAdd) { Text("Add") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRemoveOne) {
                        Icon(Icons.Filled.Remove, contentDescription = "Remove one ${drink.name}")
                    }
                    Text("$qty", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = "Add one ${drink.name}")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MenuScreenPreview() {
    SodaMeshTheme(dynamicColor = false) {
        MenuScreen(cart = mapOf("cola" to 2, "orange" to 1))
    }
}

@Preview(showBackground = true)
@Composable
private fun MenuScreenEmptyPreview() {
    SodaMeshTheme(dynamicColor = false) {
        MenuScreen()
    }
}
