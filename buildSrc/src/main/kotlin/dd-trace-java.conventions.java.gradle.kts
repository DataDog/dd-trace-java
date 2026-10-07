plugins {
  id("dd-trace-java.dependency-locking")
  // Expose Java accessors while the convention still delegates to script plugins.
  java
}

apply(from = rootDir.resolve("gradle/java_deps.gradle"))
apply(from = rootDir.resolve("gradle/java_no_deps.gradle"))
