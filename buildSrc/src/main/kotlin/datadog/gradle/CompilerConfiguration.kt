package datadog.gradle

import groovy.lang.Closure
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.AbstractCompile
import org.gradle.kotlin.dsl.extra

fun AbstractCompile.configureCompiler(
  toolchainVersion: Int,
  targetVersion: JavaVersion,
  unsetReleaseFlagReason: String = "",
) {
  (project.extra["configureCompiler"] as Closure<*>).call(
    this,
    toolchainVersion,
    targetVersion,
    unsetReleaseFlagReason,
  )
}
