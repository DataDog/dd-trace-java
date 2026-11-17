pluginManagement {
  repositories {
    mavenLocal()
    providers.gradleProperty("gradlePluginProxy").orNull?.let { proxy ->
      maven {
        url = uri(proxy)
        isAllowInsecureProtocol = true
      }
    }
    providers.gradleProperty("mavenRepositoryProxy").orNull?.let { proxy ->
      maven {
        url = uri(proxy)
        isAllowInsecureProtocol = true
      }
    }
    gradlePluginPortal()
  }
}

include(":call-site-instrumentation-plugin")
include(":modifiable-config-agent")

dependencyResolutionManagement {
  versionCatalogs {
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}
