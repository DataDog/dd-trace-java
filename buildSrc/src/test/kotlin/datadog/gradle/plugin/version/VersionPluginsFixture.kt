package datadog.gradle.plugin.version

import datadog.gradle.plugin.GradleFixture
import java.io.File

open class VersionPluginsFixture : GradleFixture() {
  val generatedVersionFile: File get() = file("build/generated/version/my-lib.version")

  val builtResourceVersionFile: File get() = file("build/resources/main/my-lib.version")
}
