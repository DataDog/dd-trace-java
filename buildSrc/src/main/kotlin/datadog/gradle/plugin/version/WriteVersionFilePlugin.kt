package datadog.gradle.plugin.version

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the

class WriteVersionFilePlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.pluginManager.apply("java")

    val writeVersionFile = target.tasks.register<WriteVersionFile>("writeVersionNumberFile")

    target.the<JavaPluginExtension>().sourceSets.named("main") {
      resources.srcDir(writeVersionFile)
    }
  }
}
