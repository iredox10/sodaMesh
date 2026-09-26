package com.sodamesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sodamesh.perms.PermissionManager
import com.sodamesh.perms.rememberMeshPermissionLauncher
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Flavor-neutral nav root contract.
 *
 * Flavor source sets (`src/customer`, `src/vendor`) are exclusive per variant,
 * so code in `main` cannot reference flavor classes directly. Each flavor
 * binds its own [NavRoot] implementation (`CustomerNavRoot` / `VendorNavRoot`)
 * via a Hilt module in its source set, and [wireNav] resolves it through
 * [NavRootEntryPoint] at composition time.
 */
interface NavRoot {
    @Composable
    fun Root()
}

/** Hilt accessor for the flavor-bound [NavRoot]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NavRootEntryPoint {
    fun navRoot(): NavRoot
}

/**
 * Root content lambda for the given flavor; [MainActivity] calls the returned
 * lambda inside `setContent`. The [isVendor] parameter is retained for call
 * compatibility — the actual root comes from the flavor-bound [NavRoot].
 *
 * The root is gated on [PermissionGate] first, then renders the flavor nav.
 */
@Suppress("UNUSED_PARAMETER")
fun wireNav(isVendor: Boolean): @Composable () -> Unit = {
    PermissionGate {
        val context = LocalContext.current
        val root = remember(context) {
            runCatching {
                EntryPointAccessors
                    .fromApplication(context.applicationContext, NavRootEntryPoint::class.java)
                    .navRoot()
            }.getOrNull()
        }
        if (root != null) {
            root.Root()
        } else {
            Text(
                text = "SodaMesh UI unavailable for this flavor",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * Blocks [content] until every [PermissionManager.requiredPerms] entry is
 * granted (i.e. [PermissionManager.missingPerms] is empty — the runtime
 * equivalent of "all granted").
 *
 * Shows the per-permission rationale ([PermissionManager.rationaleFor]) plus
 * a grant button driven by [rememberMeshPermissionLauncher]. The missing set
 * is re-queried authoritatively after each launcher result and on every
 * `ON_RESUME` (covers grants made via system Settings).
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(PermissionManager.missingPerms(context)) }
    val launcher = rememberMeshPermissionLauncher {
        missing = PermissionManager.missingPerms(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                missing = PermissionManager.missingPerms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (missing.isEmpty()) {
        content()
    } else {
        PermissionGateScreen(
            missing = missing,
            onGrant = { launcher.launch(PermissionManager.requiredPerms().toTypedArray()) },
        )
    }
}

@Composable
private fun PermissionGateScreen(
    missing: List<String>,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Permissions needed",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        // Distinct by rationale text: several permissions share one
        // explanation (e.g. legacy BLUETOOTH + BLUETOOTH_ADMIN).
        missing.map(PermissionManager::rationaleFor).distinct().forEach { rationale ->
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onGrant) {
            Text("Grant permissions")
        }
    }
}
