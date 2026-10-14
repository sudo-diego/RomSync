import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.sudodiego.romsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sudodiego.romsync"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Igual que en TBF Companion: keystore.properties (fuera de git) apunta a la clave propia; sin él, clave de depuración.
    val keyProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        // Clave fija para las APK de GitHub Actions: sin ella cada compilación salía firmada con otra clave de
        // depuración y Android obligaba a desinstalar (y se perdían los ajustes). No es secreta: es una app personal.
        create("shared") {
            storeFile = file("romsync-shared.keystore")
            storePassword = "romsync"
            keyAlias = "romsync"
            keyPassword = "romsync"
        }
        if (keyProps != null) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES") }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Carátulas desde el PC.
    implementation("io.coil-kt:coil-compose:2.7.0")
    // PC por SFTP (el OpenSSH de Windows), como Nixel.
    implementation("com.github.mwiede:jsch:0.2.21")
    // La Cocina: servidor web en la tablet (estilo Nonna's Kitchen de Cannoli) y su código QR.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    // Respaldo automático de partidas.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Shizuku: leer y escribir las partidas que los emuladores guardan en Android/data.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.apache.sshd:sshd-core:2.14.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.14.0")
    testImplementation("org.slf4j:slf4j-nop:2.0.16")
}
