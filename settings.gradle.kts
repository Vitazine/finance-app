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
        mavenCentral()
    }
}

rootProject.name = "FinanceApp"
include(":shared")
if (!providers.gradleProperty("coreOnly").map(String::toBoolean).getOrElse(false)) {
    include(":androidApp")
}
