/*
 * Temporary Java-only counterpart of `dd-trace-java.conventions.java` while modules migrate from
 * Groovy/Spock to Java/JUnit 5. Migrated modules use this pure Java convention (no `groovy` plugin,
 * Spock or CodeNarc), while modules not yet migrated keep using `dd-trace-java.conventions.java`.
 * Once all modules are migrated, merge this with `dd-trace-java.conventions.java`.
 */
plugins {
  id("dd-trace-java.dependency-locking")
  java
}

apply(from = rootDir.resolve("gradle/java_no_deps.gradle"))
apply(from = rootDir.resolve("gradle/java_test_deps.gradle"))
