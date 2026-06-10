plugins {
  `java-library`
  id("dd-trace-java.module.internal-component")
}

description = "Metrics agent"

dependencies {
  api(project(":products:metrics:metrics-api"))
}
