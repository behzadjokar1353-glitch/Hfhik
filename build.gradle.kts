plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ir.behzad.accounting"
    compileSdk = 35
    defaultConfig {
        applicationId = "ir.behzad.accounting"
        minSdk = 23
        targetSdk = 35
        versionCode = 21
        versionName = "4.3.9"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
