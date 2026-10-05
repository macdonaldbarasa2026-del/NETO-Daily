plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.netodaily.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netodaily.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

        val supabaseUrl = System.getenv("SUPABASE_URL")
            ?: project.findProperty("SUPABASE_URL") as String?
            ?: "https://vjzelobnfjvfchoiqbqr.supabase.co"

        val supabaseKey = System.getenv("SUPABASE_PUBLISHABLE_KEY")
            ?: project.findProperty("SUPABASE_PUBLISHABLE_KEY") as String?
            ?: ""

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabaseKey\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:3.5.0"))

    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")

    implementation("io.ktor:ktor-client-android:3.0.3")
    implementation("io.ktor:ktor-client-websockets:3.0.3")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.activity:activity-ktx:1.10.1")
}
