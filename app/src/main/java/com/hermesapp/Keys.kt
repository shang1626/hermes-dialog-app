package com.hermesapp

/**
 * 构建期注入的配置。
 *
 * 真值不写进源码：本地开发时由 local.properties（已被 .gitignore 排除）在构建期
 * 通过 BuildConfig 注入；公开仓库里的源码这些字段为空，部署者自行在
 * local.properties 里填写自己的服务域名、更新域名与密钥。
 */
object Keys {
    // R20：APP_PASSWORD / DEFAULT_KEY / FRIEND_KEY 已删除——它们会被编成明文字符串进
    // classes.dex，而更新包是公开可下载的。App 改用「账号:密码」登录，凭据存手机本地
    // （见 Prefs.credential 与服务端补丁 apply_app_login_patch.py）。
    val UPDATE_URL = BuildConfig.UPDATE_URL
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
