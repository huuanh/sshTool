package com.sshtool.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sshtool.app.ui.connections.ConnectionFormScreen
import com.sshtool.app.ui.connections.ConnectionListScreen
import com.sshtool.app.ui.keys.KeyManagementScreen
import com.sshtool.app.ui.portforward.PortForwardScreen
import com.sshtool.app.ui.settings.SettingsScreen
import com.sshtool.app.ui.sftp.SftpScreen
import com.sshtool.app.ui.snippets.SnippetScreen
import com.sshtool.app.ui.terminal.TerminalScreen

object Routes {
    const val CONNECTION_LIST = "connections"
    const val CONNECTION_FORM = "connection_form/{connectionId}"
    const val TERMINAL = "terminal/{connectionId}"
    const val KEYS = "keys"
    const val SFTP = "sftp/{sessionId}"
    const val SETTINGS = "settings"
    const val SNIPPETS = "snippets/{sessionId}"
    const val PORT_FORWARDS = "port_forwards/{connectionId}/{sessionId}"

    fun connectionForm(connectionId: Long = -1L) = "connection_form/$connectionId"
    fun terminal(connectionId: Long) = "terminal/$connectionId"
    fun sftp(sessionId: String) = "sftp/$sessionId"
    fun snippets(sessionId: String) = "snippets/$sessionId"
    fun portForwards(connectionId: Long, sessionId: String) =
        "port_forwards/$connectionId/$sessionId"
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.CONNECTION_LIST) {
        composable(Routes.CONNECTION_LIST) {
            ConnectionListScreen(
                onAddConnection = { navController.navigate(Routes.connectionForm()) },
                onEditConnection = { id -> navController.navigate(Routes.connectionForm(id)) },
                onConnect = { id -> navController.navigate(Routes.terminal(id)) },
                onOpenKeys = { navController.navigate(Routes.KEYS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = Routes.CONNECTION_FORM,
            arguments = listOf(navArgument("connectionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getLong("connectionId") ?: -1L
            ConnectionFormScreen(
                connectionId = connectionId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.TERMINAL,
            arguments = listOf(navArgument("connectionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getLong("connectionId") ?: -1L
            TerminalScreen(
                connectionId = connectionId,
                onBack = { navController.popBackStack() },
                onOpenSftp = { sessionId -> navController.navigate(Routes.sftp(sessionId)) },
                onOpenSnippets = { sessionId -> navController.navigate(Routes.snippets(sessionId)) },
                onOpenPortForwards = { connId, sessionId ->
                    navController.navigate(Routes.portForwards(connId, sessionId))
                }
            )
        }

        composable(Routes.KEYS) {
            KeyManagementScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.SFTP,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            SftpScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.SNIPPETS,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            SnippetScreen(
                sessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PORT_FORWARDS,
            arguments = listOf(
                navArgument("connectionId") { type = NavType.LongType },
                navArgument("sessionId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val connectionId = backStackEntry.arguments?.getLong("connectionId") ?: -1L
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            PortForwardScreen(
                connectionId = connectionId,
                sessionId = sessionId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
