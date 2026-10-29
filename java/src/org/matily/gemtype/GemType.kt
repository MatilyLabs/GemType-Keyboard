// Copyright 2026 Matily. SPDX-License-Identifier: AGPL-3.0-or-later

package org.matily.gemtype

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.android.inputmethod.latin.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.matily.gemtype.core.GeminiClient
import org.matily.gemtype.core.HttpTransport
import org.matily.gemtype.core.VpnState

/** Process-wide entry points for GemType. */
object GemType {
    /**
     * False in the offline flavor (no INTERNET permission), where GemType is hidden, and below
     * API 23, which the encrypted key store needs.
     */
    @JvmStatic
    val isEnabled: Boolean
        get() = BuildConfig.GEMTYPE_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** One client for the process so keep-alive connections are shared by every request. */
    val client: GeminiClient by lazy { GeminiClient(HttpTransport(BuildConfig.GEMTYPE_API_BASE)) }

    /**
     * Scope for work that must outlive any one screen, such as the settings "Test key"
     * button. The panel owns its own scope for the requests it can cancel.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + uncaught())

    /** Called once from the Application: lets error messages mention a VPN when one is active. */
    @JvmStatic
    fun init(context: Context) {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        VpnState.isActive = {
            try {
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } catch (e: RuntimeException) {
                false
            }
        }
    }

    /**
     * Last resort for a coroutine that forgot its own catch: log and carry on. Without it an
     * unexpected exception in a GemType request would take the whole keyboard down.
     */
    fun uncaught() = CoroutineExceptionHandler { _, e -> Log.w("GemType", "Unhandled error", e) }

    const val SETTINGS_FRAGMENT = "org.matily.gemtype.GemTypeSettingsFragment"
}
