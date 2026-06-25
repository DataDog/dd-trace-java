plugins {
  `java-library`
  id("dd-trace-java.module.internal-library")
}

description = "Metrics agent"

dependencies {
  api(project(":products:metrics:metrics-api"))
}
