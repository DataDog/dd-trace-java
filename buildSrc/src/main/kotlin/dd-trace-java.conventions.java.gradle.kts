plugins {
  id("dd-trace-java.dependency-locking")
  java
}

apply(from = rootDir.resolve("gradle/java_no_deps.gradle"))
apply(from = rootDir.resolve("gradle/java_test_deps.gradle"))
