pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "limao-unzip"

include(":engine")

// app 模块需要 Android SDK；CI 或无 SDK 环境可用 -PincludeApp=false 只构建 engine
if (providers.gradleProperty("includeApp").getOrElse("true").toBoolean()) {
    include(":app")
}
