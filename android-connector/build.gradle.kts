plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.fred.pacorun.connector"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
