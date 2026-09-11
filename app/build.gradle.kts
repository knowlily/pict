import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pict.metatool"
    compileSdk = 36

    // 预设 JSON 的唯一副本在仓库根 presets/（docs/03 §7 的登记表 + 校验 schema 也放那儿），
    // 构建时同步进 assets/presets/，避免「仓库一份、APK 一份」双维护。
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/presetAssets"))

    defaultConfig {
        applicationId = "com.pict.metatool"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("zh", "en")
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // 元数据读取/写入（docs/03 §4）
    implementation(libs.androidx.exifinterface)
    implementation(libs.metadata.extractor)
    implementation(libs.commons.imaging)
    implementation(libs.xmpcore)

    // 预设 JSON 解析（T3.1，domain/preset）
    implementation(libs.kotlinx.serialization.json)

    // 协程：任务层（domain/job，T5.1/T5.2）显式声明；之前只是 Compose 传递进来的
    implementation(libs.kotlinx.coroutines.core)

    // 图片加载（缩略图网格 T1.9）
    implementation(libs.coil)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * 把仓库根 `presets` 目录下的 JSON 同步到生成目录，作为 assets 的一部分打进 APK。
 *
 * 注意：Kotlin 块注释可嵌套，注释里别出现「斜杠 + 星号」，否则从那里开始整段被当成注释，
 * 后面的任务注册会静默消失（本文件踩过一次：任务不存在，但构建仍然是 SUCCESSFUL）。
 *
 * 用 Sync 而不是往 `src/main/assets/` 拷一份：预设只保留一处真相（仓库根，和 docs/03 §7
 * 的登记表、schema 放一起），改完 JSON 直接生效，不会出现「仓库改了、APK 里还是旧的」。
 */
val syncPresets by tasks.registering(Sync::class) {
    description = "同步仓库根 presets/*.json 到 assets/presets/"
    group = "build"
    from(rootProject.file("presets")) {
        include("*.json")
        into("presets")
    }
    into(layout.buildDirectory.dir("generated/presetAssets"))
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncPresets) }
