pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        jcenter()
        maven("https://jitpack.io/")
        maven("https://artifactory.appodeal.com/appodeal-public/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://www.jitpack.io/")
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "VCAMSX"
include(":app")
