package com.sodamesh.customer

import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import com.sodamesh.NavRoot
import com.sodamesh.navigation.SodaNav
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Customer-flavor [NavRoot]: menu → cart → send navigation over the shared
 * activity-scoped [CustomerViewModel].
 *
 * Lives in the customer source set because it references customer-only types
 * ([CustomerViewModel] and the customer routes); `main` sees only [NavRoot].
 */
class CustomerNavRoot @Inject constructor() : NavRoot {
    @Composable
    override fun Root() {
        val navController = rememberNavController()
        val vm = sharedCustomerViewModel()
        // Attaches the Hilt MeshSender to the shared VM for the whole flow.
        AttachMeshSender(vm)
        SodaNav(
            navController = navController,
            isVendor = false,
            menuScreen = { MenuRoute(navController = navController, vm = vm) },
            cartScreen = { CartRoute(navController = navController, vm = vm) },
            sendScreen = { SendRoute(navController = navController, vm = vm) },
        )
    }
}

/** Binds the customer nav root for the flavor Hilt graph. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CustomerNavModule {

    @Binds
    abstract fun bindNavRoot(impl: CustomerNavRoot): NavRoot
}
