import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ksp)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}

// kdk: version from CI (-PkdkVersionCode=<git rev-list --count HEAD>, -PkdkVersionName=<git describe>).
val kdkVersionCode = providers.gradleProperty("kdkVersionCode").orNull?.toIntOrNull() ?: 1
val kdkVersionName = providers.gradleProperty("kdkVersionName").orNull ?: "dev"

// kdk: stable signing key. Only when BOTH env vars are set (CI with repo secrets) is the
// debug signingConfig pointed at the kdk PKCS12 keystore (alias thud-kdk, one password for
// store and key). Otherwise the default debug key stays, so local builds keep working.
val kdkKeystoreB64 = providers.environmentVariable("KDK_KEYSTORE_B64").orNull?.takeIf { it.isNotBlank() }
val kdkKeystorePass = providers.environmentVariable("KDK_KEYSTORE_PASS").orNull?.takeIf { it.isNotBlank() }
val kdkKeystoreFile: File? = if (kdkKeystoreB64 != null && kdkKeystorePass != null) {
    layout.buildDirectory.file("kdk/thud-kdk.p12").get().asFile.also { f ->
        f.parentFile.mkdirs()
        f.writeBytes(Base64.getMimeDecoder().decode(kdkKeystoreB64))
    }
} else null

android {
    namespace = "io.github.avikulin.thud"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.avikulin.thud"
        minSdk = 33
        targetSdk = 35 
        versionCode = kdkVersionCode
        versionName = kdkVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (kdkKeystoreFile != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = kdkKeystoreFile
                storeType = "PKCS12"
                storePassword = kdkKeystorePass
                keyAlias = "thud-kdk"
                keyPassword = kdkKeystorePass
            }
        }
    }

    buildTypes {
        // debug stays debuggable (default): tm-install/tm-smoke rely on run-as.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // gRPC dependencies
    implementation(libs.okhttp)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.protobuf.javalite)
    implementation(libs.javax.annotation)

    // Room database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Lifecycle (for coroutines and Flow)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    // Activity KTX (for viewModels delegate)
    implementation(libs.activity.ktx)

    // Fragment KTX (for viewModels delegate in fragments)
    implementation(libs.fragment.ktx)

    // Garmin FIT SDK (for workout export)
    implementation(libs.garmin.fit)

    // Encrypted SharedPreferences (for Garmin Connect tokens)
    implementation(libs.security.crypto)
}