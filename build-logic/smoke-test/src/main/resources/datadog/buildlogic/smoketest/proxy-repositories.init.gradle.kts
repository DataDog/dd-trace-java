import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.initialization.Settings

gradle.beforeSettings(Action<Settings> {
  val gradlePluginProxy = providers.gradleProperty("gradlePluginProxy").orNull
  val mavenRepositoryProxy = providers.gradleProperty("mavenRepositoryProxy").orNull

  pluginManagement {
    repositories {
      mavenLocal()
      gradlePluginProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          url = java.net.URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      mavenRepositoryProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          url = java.net.URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      gradlePluginPortal()
      mavenCentral()
    }
  }

  gradle.beforeProject(Action<Project> {
    repositories {
      mavenLocal()
      mavenRepositoryProxy?.takeIf { it.isNotBlank() }?.let { proxy ->
        maven {
          url = java.net.URI(proxy)
          isAllowInsecureProtocol = true
        }
      }
      mavenCentral()
    }
  })
})
