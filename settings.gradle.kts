pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 液态玻璃（FR-35）用的 Kyant0/AndroidLiquidGlass 只发到 JitPack，Maven Central 上没有。
        // 收窄到这一个 group，别让别的依赖绕到 JitPack 上解析（它慢，而且同一个 tag 能不能解析全看它心情）。
        // 本机还得在 ~/.gradle/init.d/aliyun-mirror.gradle 里也加一份：那个脚本会 clear() 掉这里，
        // 换成阿里云镜像（这台机器取不到 dl.google.com），所以只写在这里是解析不到的。
        maven("https://jitpack.io") {
            content { includeGroup("com.github.Kyant0") }
        }
    }
}

rootProject.name = "Pict"
include(":app")
