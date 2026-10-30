plugins {
  base
  alias(libs.plugins.spotless) apply false
}

tasks.check {
  dependsOn(subprojects.map { "${it.path}:check" })
}
