package com.hermesapp

/** Self-use build: profile keys + app login gate. */
object Keys {
    const val APP_PASSWORD = "37201"
    const val DEFAULT_KEY = "d86e7f8e77dc84282977be143ea8f90da544da3ff376f619220a8c5f61bee372"
    const val FRIEND_KEY = "ba5fc82fdcf821d18857c4edc476e804a828819ed0d6257e95f892d757fec260"
    // 2026-10-06 起走腾讯 EdgeOne（国内节点，首字节约 0.25s，原 CF 约 1.1s）。
    // 对话走 hermes.*，更新分发走 gx.*（EdgeOne 上两条独立域名，各指不同源站端口）。
    const val DEFAULT_URL = "https://hermes.99801886.xyz"
    const val UPDATE_URL = "https://gx.99801886.xyz/update/version.json"
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
