import datadog.gradle.plugin.testJvmConstraints.ProvideJvmArgsOnJvmLauncherVersion
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension.Companion.TEST_JVM_CONSTRAINTS
import datadog.gradle.plugin.testJvmConstraints.TestJvmSpec
import datadog.gradle.plugin.testJvmConstraints.isJavaVersionAllowed
import datadog.gradle.plugin.testJvmConstraints.isTestJvmAllowed

plugins {
  java
}

val projectExtension = extensions.create<TestJvmConstraintsExtension>(TEST_JVM_CONSTRAINTS)

val testJvmSpec = TestJvmSpec(project)

tasks.withType<Test>().configureEach {
  if (extensions.findByName(TEST_JVM_CONSTRAINTS) != null) {
    return@configureEach
  }

  inputs.property("testJvm", testJvmSpec.testJvmProperty).optional(true)

  val taskExtension = project.objects.newInstance<TestJvmConstraintsExtension>().also {
    configureConventions(it, projectExtension)
  }

  inputs.property("$TEST_JVM_CONSTRAINTS.allowReflectiveAccessToJdk", taskExtension.allowReflectiveAccessToJdk).optional(true)
  inputs.property("$TEST_JVM_CONSTRAINTS.excludeJdk", taskExtension.excludeJdk)
  inputs.property("$TEST_JVM_CONSTRAINTS.includeJdk", taskExtension.includeJdk)
  inputs.property("$TEST_JVM_CONSTRAINTS.forceJdk", taskExtension.forceJdk)
  inputs.property("$TEST_JVM_CONSTRAINTS.minJavaVersion", taskExtension.minJavaVersion).optional(true)
  inputs.property("$TEST_JVM_CONSTRAINTS.maxJavaVersion", taskExtension.maxJavaVersion).optional(true)

  extensions.add(TEST_JVM_CONSTRAINTS, taskExtension)

  configureTestJvm(taskExtension)
}

/**
 * Provide arguments if condition is met.
 */
fun Test.conditionalJvmArgs(
  applyFromVersion: JavaVersion,
  jvmArgsToApply: List<String>,
  additionalCondition: Provider<Boolean> = project.providers.provider { true }
) {
  jvmArgumentProviders.add(
    ProvideJvmArgsOnJvmLauncherVersion(
      this,
      applyFromVersion,
      jvmArgsToApply,
      additionalCondition
    )
  )
}

/**
 * Configure the jvm launcher of the test task and ensure the test task
 * can be run with the test task launcher.
 */
private fun Test.configureTestJvm(extension: TestJvmConstraintsExtension) {
  if (testJvmSpec.javaTestLauncher.isPresent) {
    javaLauncher = testJvmSpec.javaTestLauncher
    onlyIf("Allowed or forced JDK") {
      extension.isTestJvmAllowed(testJvmSpec)
    }
  } else {
    onlyIf("Is current Daemon JVM  allowed") {
      extension.isJavaVersionAllowed(JavaVersion.current())
    }
  }

  // temporary workaround when using Java16+: some tests require reflective access to java.lang/java.util
  conditionalJvmArgs(
    JavaVersion.VERSION_16,
    listOf(
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED"
    ),
    extension.allowReflectiveAccessToJdk
  )
}

// Jacoco plugin is not applied on every project
pluginManager.withPlugin("org.gradle.jacoco") {
  tasks.withType<Test>().configureEach {
    // Disable jacoco for additional 'testJvm' tests to speed things up a bit
    if (testJvmSpec.javaTestLauncher.isPresent) {
      extensions.configure<JacocoTaskExtension> {
        isEnabled = false
      }
    }
  }
}

/**
 * Configures the convention, this tells Gradle where to look for values.
 *
 * Currently, the extension is still configured to look at project's _extra_ properties.
 */
private fun Test.configureConventions(
  taskExtension: TestJvmConstraintsExtension,
  projectExtension: TestJvmConstraintsExtension
) {
  taskExtension.minJavaVersion.convention(
    projectExtension.minJavaVersion
      .orElse(providers.provider { project.findProperty("${name}MinJavaVersionForTests") as? JavaVersion })
      .orElse(providers.provider { project.findProperty("minJavaVersion") as? JavaVersion })
  )
  taskExtension.maxJavaVersion.convention(
    projectExtension.maxJavaVersion
      .orElse(providers.provider { project.findProperty("${name}MaxJavaVersionForTests") as? JavaVersion })
      .orElse(providers.provider { project.findProperty("maxJavaVersion") as? JavaVersion })
  )
  taskExtension.forceJdk.convention(
    projectExtension.forceJdk
      .orElse(
        providers.provider {
          @Suppress("UNCHECKED_CAST")
          project.findProperty("forceJdk") as? List<String> ?: emptyList()
        }
      )
  )
  taskExtension.excludeJdk.convention(
    projectExtension.excludeJdk
      .orElse(
        providers.provider {
          @Suppress("UNCHECKED_CAST")
          project.findProperty("excludeJdk") as? List<String> ?: emptyList()
        }
      )
  )
  taskExtension.allowReflectiveAccessToJdk.convention(
    projectExtension.allowReflectiveAccessToJdk
      .orElse(providers.provider { project.findProperty("allowReflectiveAccessToJdk") as? Boolean })
  )
}
