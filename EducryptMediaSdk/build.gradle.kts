plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("io.realm.kotlin")
    kotlin("kapt")
}

android {
    namespace = "com.appsquadz.educryptmedia"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    val retrofitVersion = "2.11.0"
    val interceptorVersion = "4.12.0"
    val scalarVersion = "2.11.0"

    val realmVersion = "2.0.0"
    val hiltVersion = "2.56.2"


    //Media3
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-ui-leanback:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    //Retrofit
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$interceptorVersion")
    implementation("com.squareup.retrofit2:converter-scalars:$scalarVersion")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    //Secure
    implementation("androidx.security:security-crypto-ktx:1.1.0-alpha07")

    //RealmVersion
    implementation("io.realm.kotlin:library-base:${realmVersion}")

}