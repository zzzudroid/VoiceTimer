import java.io.File
import java.net.URI
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Параметры подписи читаем из несинхронизируемого keystore.properties (в .gitignore)
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.voicetimer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicetimer"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Юнит-тесты доменной логики (парсер, числительные, повторы) — чистый JVM, без эмулятора
    testImplementation("junit:junit:4.13.2")
}

// Скачивает и распаковывает русскую модель Vosk в assets (модель не хранится в git).
// Запуск: ./gradlew fetchVoskModel   (на Windows: gradlew.bat fetchVoskModel)
tasks.register("fetchVoskModel") {
    description = "Скачивает русскую модель Vosk в app/src/main/assets/vosk-model-ru"
    group = "build setup"

    val modelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    val assetsDir = file("src/main/assets")
    val modelDir = file("src/main/assets/vosk-model-ru")

    // Не качаем повторно, если ключевой файл модели уже на месте
    onlyIf { !file("src/main/assets/vosk-model-ru/am/final.mdl").exists() }

    doLast {
        val zipFile = layout.buildDirectory.file("vosk-model.zip").get().asFile
        zipFile.parentFile.mkdirs()
        logger.lifecycle("Скачиваю модель Vosk (~46 МБ)…")
        URI(modelUrl).toURL().openStream().use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }

        logger.lifecycle("Распаковываю модель в $modelDir …")
        modelDir.deleteRecursively()
        assetsDir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // Убираем верхний уровень (vosk-model-small-ru-0.22/...)
                val rel = entry.name.substringAfter('/')
                if (rel.isNotEmpty()) {
                    val out = File(modelDir, rel)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        zipFile.delete()
        logger.lifecycle("Модель Vosk готова.")
    }
}

// Гарантируем наличие модели перед сборкой ресурсов
tasks.named("preBuild") {
    dependsOn("fetchVoskModel")
}

