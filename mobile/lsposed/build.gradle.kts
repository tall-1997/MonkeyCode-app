plugins {
    id("com.android.application") version "9.2.1"
}

android {
    namespace = "com.monkeycode.hook"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.monkeycode.hook"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs["debug"]
        }
    }

    lint {
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").java.srcDir("../native-android/kotlin/com/monkeycode/hook")
    }

}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
