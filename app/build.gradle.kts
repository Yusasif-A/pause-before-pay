import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Secrets live in local.properties (gitignored) and are surfaced as BuildConfig
 * fields. Nothing secret is ever hard-coded in source. A missing value yields an
 * empty string so the project still builds for anyone who clones it; the app then
 * says plainly that no API key is configured.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String = (localProps.getProperty(name) ?: "").trim()

android {
    namespace = "com.pausebeforepay"
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.pausebeforepay"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PRESAGE_API_KEY", "\"${secret("PRESAGE_API_KEY")}\"")
        buildConfigField("String", "GEMMA_BASE_URL", "\"${secret("GEMMA_BASE_URL")}\"")
        buildConfigField("String", "GEMMA_MODEL", "\"${secret("GEMMA_MODEL")}\"")
        buildConfigField("String", "GEMMA_API_KEY", "\"${secret("GEMMA_API_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.security.crypto)
    implementation(libs.presage.smartspectra)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
