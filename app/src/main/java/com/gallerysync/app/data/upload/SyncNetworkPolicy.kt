package com.gallerysync.app.data.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.NetworkType
import com.gallerysync.app.data.localdb.Preferences

/**
 * Centralized network eligibility policy for GallerySync automatic and manual uploads.
 *
 * Requirements (Phase 7):
 * - Automatic uploads must run ONLY on an eligible unmetered Wi-Fi connection.
 * - Mobile data must NEVER be used for automatic media uploads.
 * - Metered Wi-Fi is rejected by default, unless explicitly enabled by user preference.
 * - No-network conditions leave queue items safely pending.
 * - Manual uploads default to Wi-Fi policy, but can optionally use mobile data if explicitly enabled.
 */
object SyncNetworkPolicy {

    private const val TAG = "SyncNetworkPolicy"

    sealed class NetworkStatus {
        object Eligible : NetworkStatus()
        object NoNetwork : NetworkStatus()
        object DisallowedMobileData : NetworkStatus()
        object DisallowedMeteredWifi : NetworkStatus()
        object IneligibleOther : NetworkStatus()

        val isEligible: Boolean get() = this is Eligible
    }

    @VisibleForTesting
    var networkStatusOverride: NetworkStatus? = null

    /**
     * Evaluates whether the current active network meets eligibility requirements.
     *
     * @param context Android context to access ConnectivityManager
     * @param isManual true if triggered as a user-initiated manual upload; false for automatic background sync
     */
    fun evaluateNetwork(context: Context, isManual: Boolean = false): NetworkStatus {
        networkStatusOverride?.let { return it }

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus.NoNetwork

        val activeNetwork: Network = connectivityManager.activeNetwork ?: return NetworkStatus.NoNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkStatus.NoNetwork

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) {
            return NetworkStatus.NoNetwork
        }

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                connectivityManager.isActiveNetworkMetered

        // 1. Manual upload evaluation
        if (isManual) {
            if (isCellular) {
                return if (Preferences.allowManualMobileDataUpload()) {
                    NetworkStatus.Eligible
                } else {
                    NetworkStatus.DisallowedMobileData
                }
            }
            if (isWifi) {
                return if (isMetered && !Preferences.allowMeteredWifiBackup()) {
                    NetworkStatus.DisallowedMeteredWifi
                } else {
                    NetworkStatus.Eligible
                }
            }
            return if (isMetered && !Preferences.allowMeteredWifiBackup()) {
                NetworkStatus.DisallowedMeteredWifi
            } else {
                NetworkStatus.Eligible
            }
        }

        // 2. Automatic backup evaluation
        // RULE: Mobile data must NEVER be used for automatic media uploads.
        if (isCellular) {
            return NetworkStatus.DisallowedMobileData
        }

        // Automatic backup requires Wi-Fi (or Ethernet)
        if (!isWifi) {
            return NetworkStatus.IneligibleOther
        }

        // Metered Wi-Fi evaluation: default rejected unless explicitly allowed
        if (isMetered && !Preferences.allowMeteredWifiBackup()) {
            return NetworkStatus.DisallowedMeteredWifi
        }

        return NetworkStatus.Eligible
    }

    /**
     * Helper to test if current network is eligible.
     */
    fun isNetworkEligible(context: Context, isManual: Boolean = false): Boolean {
        return evaluateNetwork(context, isManual).isEligible
    }

    /**
     * Builds WorkManager constraints reflecting the policy.
     *
     * For automatic backup:
     * - By default: requires UNMETERED network.
     * - If user enabled metered Wi-Fi: requires CONNECTED (and worker enforces TRANSPORT_WIFI).
     *
     * For manual uploads:
     * - If mobile data is allowed: requires CONNECTED.
     * - Otherwise: requires UNMETERED (or CONNECTED if metered Wi-Fi is enabled).
     */
    fun getWorkManagerConstraints(isManual: Boolean = false): Constraints {
        val requiredNetworkType = when {
            isManual && Preferences.allowManualMobileDataUpload() -> NetworkType.CONNECTED
            Preferences.allowMeteredWifiBackup() -> NetworkType.CONNECTED
            else -> NetworkType.UNMETERED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(requiredNetworkType)
            .build()
    }

    /**
     * Returns a user-friendly UI description for the current network condition.
     */
    fun getNetworkStatusDescription(context: Context, isManual: Boolean = false): String {
        return when (evaluateNetwork(context, isManual)) {
            is NetworkStatus.Eligible -> "Ready to sync"
            is NetworkStatus.NoNetwork -> "No network"
            is NetworkStatus.DisallowedMobileData -> "Waiting for Wi-Fi"
            is NetworkStatus.DisallowedMeteredWifi -> "Waiting for unmetered network"
            is NetworkStatus.IneligibleOther -> "Waiting for Wi-Fi"
        }
    }

    /**
     * Registers a push-based network change listener to trigger queue processing
     * when eligible network connectivity is restored.
     */
    fun registerNetworkCallback(context: Context, onEligibleNetworkAvailable: () -> Unit): ConnectivityManager.NetworkCallback? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isNetworkEligible(context)) {
                    Log.d(TAG, "Eligible network restored -> triggering callback")
                    onEligibleNetworkAvailable()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (isNetworkEligible(context)) {
                    Log.d(TAG, "Eligible network capabilities confirmed -> triggering callback")
                    onEligibleNetworkAvailable()
                }
            }
        }
        return try {
            cm.registerDefaultNetworkCallback(callback)
            callback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
            null
        }
    }

    /**
     * Unregisters the network change listener.
     */
    fun unregisterNetworkCallback(context: Context, callback: ConnectivityManager.NetworkCallback) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) { }
    }
}
