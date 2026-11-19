plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes a private PKCS#12 key from GitHub Secrets. Local Android Studio
// builds without these variables keep Android's normal per-machine debug key.
val fixedDebugStore = providers.environmentVariable("PLATE_DEBUG_KEYSTORE_PATH").orNull
val fixedDebugPassword = providers.environmentVariable("PLATE_DEBUG_KEYSTORE_PASSWORD").orNull

android {
    namespace = "com.example.platerecognition"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.platerecognition"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!fixedDebugStore.isNullOrBlank()) {
            create("fixedDebug") {
                require(!fixedDebugPassword.isNullOrBlank()) { "Fixed debug password is missing" }
                storeFile = file(fixedDebugStore)
                require(storeFile?.isFile == true) { "Fixed debug keystore is missing" }
                storeType = "pkcs12"
                storePassword = fixedDebugPassword
                keyAlias = "plate-debug"
                keyPassword = fixedDebugPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!fixedDebugStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Models stay at their original upstream paths, rather than adding another
    // ~80 MB of binary blobs to the repository. This directory is build output.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/modelAssets"))
    androidResources { noCompress += "onnx" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val stageModels by tasks.registering(Copy::class) {
    from("../../PLateDetection_yolov5/test/yolov5plate.onnx") { rename { "detector.onnx" } }
    from("../../PlateRecognitionOCRV6/models/medium/inference.onnx") { rename { "ocr.onnx" } }
    into(layout.buildDirectory.dir("generated/modelAssets"))
    doFirst {
        check(file("../../PLateDetection_yolov5/test/yolov5plate.onnx").length() > 1_000_000) {
            "YOLOv5 detector model is missing or is a Git LFS pointer"
        }
        check(file("../../PlateRecognitionOCRV6/models/medium/inference.onnx").length() > 70_000_000) {
            "PP-OCRv6 medium model is missing or is a Git LFS pointer"
        }
    }
}
// Asset merging must wait for model staging, not only APK packaging.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(stageModels) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation("junit:junit:4.13.2")
}
