plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.platerecognition"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.platerecognition"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
