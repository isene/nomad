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
    }
}

rootProject.name = "nomad"

include(":apps:tasks")
include(":apps:hyperlist")
include(":apps:relay")
include(":apps:astro")
include(":apps:watchit")
include(":apps:mail")
include(":apps:amardice")
include(":apps:rpnx")
include(":apps:vox")
include(":apps:scribe")
include(":apps:gazette")
include(":apps:ref")
include(":apps:onepage")
include(":apps:books")
include(":apps:fresh")
