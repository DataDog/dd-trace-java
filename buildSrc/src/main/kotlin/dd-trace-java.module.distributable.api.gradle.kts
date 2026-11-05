plugins {
  id("dd-trace-java.conventions.java")
  id("dd-trace-java.conventions.testing.groovy")
}

apply(from = rootDir.resolve("gradle/publish.gradle"))
