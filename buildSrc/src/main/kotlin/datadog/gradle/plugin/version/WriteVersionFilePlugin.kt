package datadog.gradle.plugin.version

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the

class WriteVersionFilePlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.pluginManager.apply("java")

    val versionFileName = "${target.name}.version"
    val writeVersionFile = target.tasks.register<WriteVersionFile>("writeVersionNumberFile")

    // Keep volatile generated version metadata from invalidating @Classpath consumers such as CodeNarc.
    // https://docs.gradle.org/current/userguide/build_cache_concepts.html#runtime_classpath_normalization
    target.normalization {
      runtimeClasspath {
        ignore(versionFileName)
      }
    }

    target.the<JavaPluginExtension>().sourceSets.named("main") {
      resources.srcDir(writeVersionFile)
    }
  }
}
