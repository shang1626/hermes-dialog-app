package com.hermesapp

/** Self-use build: profile keys + app login gate. */
object Keys {
    const val APP_PASSWORD = "37201"
    const val DEFAULT_KEY = "d86e7f8e77dc84282977be143ea8f90da544da3ff376f619220a8c5f61bee372"
    const val FRIEND_KEY = "ba5fc82fdcf821d18857c4edc476e804a828819ed0d6257e95f892d757fec260"
    // Filled in once the Cloudflare tunnel is up.
    const val DEFAULT_URL = "https://hermes.99801888.xyz"
    const val UPDATE_URL = "https://hermes.99801888.xyz/update/version.json"
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val size: Long,
)
