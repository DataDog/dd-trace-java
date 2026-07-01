include(":call-site-instrumentation-plugin")
include(":modifiable-config-agent")

dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}
