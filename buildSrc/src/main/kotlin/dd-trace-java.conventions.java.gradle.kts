plugins {
  id("dd-trace-java.dependency-locking")
}

apply(from = rootDir.resolve("gradle/java_deps.gradle"))
apply(from = rootDir.resolve("gradle/java_no_deps.gradle"))
