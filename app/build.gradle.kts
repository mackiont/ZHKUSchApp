plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "cn.edu.zhku.schedule"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.edu.zhku.schedule"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // 不做代码混淆：本项目大量依赖反射（kotlinx.serialization / DataStore），
            // 关掉 R8 换取“装上去就能跑”的确定性，体积差异只有几 MB。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.maxHeapSize = "3g"
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*"
        )
    }
}

// Robolectric 默认在测试进程里自己下载 android-all 包，CI 上会偶发
// `Failed to fetch maven artifact`，导致整个测试类直接失败。
// 改成让 Gradle 正常解析这个包（走项目配置的仓库、可用缓存），
// 测试时指向本地目录读取，Robolectric 不再联网下载。
val robolectricAndroidAll by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
    isCanBeResolved = true
}

val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")

// 测试沙箱用 @Config(sdk = [34])；Robolectric 解析资源和 manifest 时还要用
// targetSdk 对应的 compile SDK，而 4.14.1 最高只支持 35，所以两个包都要有。
val robolectricSdkJars = listOf(
    "14-robolectric-10818077-i7",
    "15-robolectric-12650502-i7"
)

val syncRobolectricDeps by tasks.registering(Copy::class) {
    description = "把 Robolectric 的 android-all 包放进测试可读的本地目录"
    from(robolectricAndroidAll)
    into(robolectricDepsDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDepsDir.get().asFile.absolutePath)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-util")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Robolectric 运行时需要的 android-all 包，交给 Gradle 统一解析（见文件下方配置）
    robolectricSdkJars.forEach {
        robolectricAndroidAll("org.robolectric:android-all-instrumented:$it")
    }
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
