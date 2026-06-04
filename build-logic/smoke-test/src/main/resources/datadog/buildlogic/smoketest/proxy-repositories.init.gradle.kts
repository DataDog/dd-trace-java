import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.kotlin.dsl.closureOf

beforeSettings(closureOf<Settings> {
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

  gradle.beforeProject(closureOf<Project> {
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
