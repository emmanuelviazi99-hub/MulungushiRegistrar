plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "zm.ac.mulungushi.registrar"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "zm.ac.mulungushi.registrar"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    // Room: the phone's database (RegistrarDatabase). The compiler generates the DAO code.
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    // WorkManager: uploads waiting changes in the background (SyncWorker)
    implementation(libs.androidx.work.runtime)
    // pull down to refresh on the roster, requests, home and sync screens
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}