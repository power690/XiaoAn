import java.io.FileOutputStream
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.xiaozhi"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.xiaozhi"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val sherpaModelUrl = "https://github.com/qwq163/Xiao-An-Voice-Assistant/releases/download/model/model.onnx"
val sherpaModelFile = layout.projectDirectory.file("src/main/assets/sherpa-model/model.onnx").asFile
val shizukuGuideUrl = "https://github.com/qwq163/Xiao-An-Voice-Assistant/releases/download/gif/shizuku_guide.gif"
val shizukuGuideFile = layout.projectDirectory.file("src/main/assets/shizuku_guide.gif").asFile

tasks.register("downloadSherpaModel") {
    outputs.file(sherpaModelFile)
    doLast {
        if (sherpaModelFile.exists() && sherpaModelFile.length() > 0L) return@doLast
        sherpaModelFile.parentFile.mkdirs()
        URL(sherpaModelUrl).openStream().use { input ->
            FileOutputStream(sherpaModelFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}

tasks.register("downloadShizukuGuide") {
    outputs.file(shizukuGuideFile)
    doLast {
        if (shizukuGuideFile.exists() && shizukuGuideFile.length() > 0L) return@doLast
        shizukuGuideFile.parentFile.mkdirs()
        URL(shizukuGuideUrl).openStream().use { input ->
            FileOutputStream(shizukuGuideFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadSherpaModel", "downloadShizukuGuide")
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    
    // 关键修复：添加 Google Material 库以支持 Theme.Material3 XML 主题
    implementation("com.google.android.material:material:1.11.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Gson
    implementation("com.google.code.gson:gson:2.10.1")
    
    // DataStore (Preferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Markdown
    implementation("com.github.jeziellago:compose-markdown:0.3.7")

    // Local Broadcast Manager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    
    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
