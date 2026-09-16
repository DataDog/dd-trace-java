rootProject.name = "test-published-dependencies"

dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}

include(":all-deps-exist")
include(":ot-pulls-in-api")
include(":ot-is-shaded")
include(":agent-logs-on-java-7")
