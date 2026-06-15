plugins {
    id("com.android.application")
}

android {
    namespace = "dev.libreqc.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.libreqc.probe"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":bmap-codec"))
    testImplementation("junit:junit:4.13.2")
}
