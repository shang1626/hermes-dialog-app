package com.hermesapp

/** Self-use build: profile keys + app login gate. */
object Keys {
    const val APP_PASSWORD = "YOUR_APP_PASSWORD"
    const val DEFAULT_KEY = "YOUR_DEFAULT_PROFILE_API_KEY"
    const val FRIEND_KEY = "YOUR_FRIEND_PROFILE_API_KEY"
    // Filled in once the Cloudflare tunnel is up.
    const val DEFAULT_URL = "https://your-gateway.example.com"
    const val UPDATE_URL = "https://your-gateway.example.com/update/version.json"
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val size: Long,
)
