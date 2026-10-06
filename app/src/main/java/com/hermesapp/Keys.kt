package com.hermesapp

/** Self-use build: profile keys + app login gate. */
object Keys {
    const val APP_PASSWORD = "YOUR_APP_PASSWORD"
    const val DEFAULT_KEY = "YOUR_DEFAULT_PROFILE_API_KEY"
    const val FRIEND_KEY = "YOUR_FRIEND_PROFILE_API_KEY"
    // 2026-10-06 起走腾讯 EdgeOne（国内节点，首字节约 0.25s，原 CF 约 1.1s）。
    // 对话走 hermes.*，更新分发走 gx.*（EdgeOne 上两条独立域名，各指不同源站端口）。
    const val DEFAULT_URL = "https://your-gateway.example.com"
    const val UPDATE_URL = "https://your-update.example.com/update/version.json"
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val size: Long,
    /** version.json 里登记的安装包 md5：下载后校验用，对不上就不交给安装器。 */
    val md5: String,
)
