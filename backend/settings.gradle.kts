rootProject.name = "group-mission"

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    ":support:common",
    ":core:domain",
    ":infra:redis",
    ":infra:rdb",
    ":app:api",
    ":app:realtime",
    ":app:worker",
)
