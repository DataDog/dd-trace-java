// Keep gradle/java.gradle as the source of truth while consumers move to plugins {}.
apply(from = rootDir.resolve("gradle/java.gradle"))
