import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension

plugins {
  id("dd-trace-java.module.testing-support")
  id("dd-trace-java.instrumentation.jdbc-tests")
}

jdbcTests {
  latestDepSuite("latestDepJava11Test")
}

dependencies {
  // Lift the Java 8 caps of the latest versions
  "latestDepJava11TestImplementation"("com.h2database:h2:+")
  "latestDepJava11TestImplementation"("com.zaxxer:HikariCP:+")
}

// latestDepJava11ForkedTest inherits the launcher and the constraints of this task
tasks.named<Test>("latestDepJava11Test") {
  if (!providers.gradleProperty("testJvm").isPresent) {
    javaLauncher = javaToolchains.launcherFor {
      languageVersion = JavaLanguageVersion.of(11)
    }
  }
  extensions.configure<TestJvmConstraintsExtension> {
    minJavaVersion = JavaVersion.VERSION_11
  }
}
