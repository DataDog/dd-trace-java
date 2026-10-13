plugins {
  base
}

tasks.check {
  dependsOn(subprojects.map { "${it.path}:check" })
}
