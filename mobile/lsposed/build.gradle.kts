plugins {
    id('com.android.library')
    id('org.jetbrains.kotlin.android')
}

android {
    namespace = "com.monkeycode.hook"
    compileSdk = 35

    defaultConfig {
        minSdk = 34
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
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("org.lsposed.lsparanoid:lsparanoid:0.6.0")
}