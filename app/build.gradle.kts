import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建期配置：真值放在 local.properties（已被 .gitignore 排除），
// 公开仓库里的源码因此不含任何密钥 / 域名。本地构建照常生效。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, def: String = ""): String = localProps.getProperty(key) ?: def

android {
    namespace = "com.hermesapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermesapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 136
        versionName = "2.125"

        // 敏感值由 local.properties 注入，源码零真值。
        buildConfigField("String", "APP_PASSWORD", "\"${cfg("HERMES_APP_PASSWORD")}\"")
        buildConfigField("String", "DEFAULT_KEY", "\"${cfg("HERMES_DEFAULT_KEY")}\"")
        buildConfigField("String", "FRIEND_KEY", "\"${cfg("HERMES_FRIEND_KEY")}\"")
        buildConfigField("String", "DEFAULT_URL", "\"${cfg("HERMES_DEFAULT_URL")}\"")
        buildConfigField("String", "UPDATE_URL", "\"${cfg("HERMES_UPDATE_URL")}\"")
        buildConfigField("String", "LEGACY_HOSTS", "\"${cfg("HERMES_LEGACY_HOSTS")}\"")
    }

    buildTypes {
        release {
            // R8：代码压缩 + 资源裁剪。debug 包这两项都不做，方法数与体积明显偏大，
            // 启动与滚动都吃这个亏。release 才是给手机装的包。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    // 流式语音播放（方案丙）：边收 audio.delta 块边播，不用等整段合成完。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
}
