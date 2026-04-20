plugins {
  `java-library`
}

description = "Metrics agent"

apply(from = rootDir.resolve("gradle/java.gradle"))

dependencies {
  api(project(":products:metrics:metrics-api"))
}
