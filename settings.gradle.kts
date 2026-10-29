rootProject.name = "movie-catalogue"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("contracts:proto", "services:media-service", "services:catalogue-service")
