import datadog.gradle.plugin.testJvmConstraints.ProvideJvmArgsOnJvmLauncherVersion
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import datadog.gradle.plugin.testJvmConstraints.TestJvmJavaLauncher
import datadog.gradle.plugin.testJvmConstraints.isJavaLauncherAllowed
import datadog.gradle.plugin.testJvmConstraints.isJavaVersionAllowed
import datadog.gradle.plugin.testJvmConstraints.isJdkExcluded
import datadog.gradle.plugin.testJvmConstraints.isJdkIncluded
import datadog.gradle.plugin.testJvmConstraints.isJdkForced

plugins {
  java
}

val projectExtension = extensions.create<TestJvmConstraintsExtension>(TestJvmConstraintsExtension.NAME)

val testJvmJavaLauncher = TestJvmJavaLauncher(project)

tasks.withType<Test>().configureEach {
  if (extensions.findByName(TestJvmConstraintsExtension.NAME) != null) {
    return@configureEach
  }

  inputs.property("testJvm", providers.gradleProperty("testJvm"))

  val taskExtension = project.objects.newInstance<TestJvmConstraintsExtension>().also {
    configureConventions(it, projectExtension)
  }

  inputs.property("${TestJvmConstraintsExtension.NAME}.allowReflectiveAccessToJdk", taskExtension.allowReflectiveAccessToJdk).optional(true)
  inputs.property("${TestJvmConstraintsExtension.NAME}.excludeJdk", taskExtension.excludeJdk)
  inputs.property("${TestJvmConstraintsExtension.NAME}.includeJdk", taskExtension.includeJdk)
  inputs.property("${TestJvmConstraintsExtension.NAME}.forceJdk", taskExtension.forceJdk)
  inputs.property("${TestJvmConstraintsExtension.NAME}.minJavaVersionForTests", taskExtension.minJavaVersionForTests).optional(true)
  inputs.property("${TestJvmConstraintsExtension.NAME}.maxJavaVersionForTests", taskExtension.maxJavaVersionForTests).optional(true)

  extensions.add(TestJvmConstraintsExtension.NAME, taskExtension)

  configureTestJvm(taskExtension)
}

// TODO make this part of the testJvm test task extension
/**
 * Provide arguments if condition is met.
 */
fun Test.configureJvmArgs(
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
fun Test.configureTestJvm(extension: TestJvmConstraintsExtension) {
  if (testJvmJavaLauncher.javaTestLauncher.isPresent) {
    javaLauncher = testJvmJavaLauncher.javaTestLauncher
    onlyIf("Allowed or forced JDK") {
      extension.isJdkIncluded(testJvmJavaLauncher.normalizedTestJvm.get()) &&
      !extension.isJdkExcluded(testJvmJavaLauncher.normalizedTestJvm.get()) &&
        (extension.isJavaLauncherAllowed(testJvmJavaLauncher.javaTestLauncher.get()) ||
          extension.isJdkForced(testJvmJavaLauncher.normalizedTestJvm.get()))
    }
  } else {
    onlyIf("Is current Daemon JVM  allowed") {
      extension.isJavaVersionAllowed(JavaVersion.current())
    }
  }

  // temporary workaround when using Java16+: some tests require reflective access to java.lang/java.util
  configureJvmArgs(
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
    if (testJvmJavaLauncher.javaTestLauncher.isPresent) {
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
  taskExtension.minJavaVersionForTests.convention(projectExtension.minJavaVersionForTests
    .orElse(providers.provider { project.findProperty("${name}MinJavaVersionForTests") as? JavaVersion })
    .orElse(providers.provider { project.findProperty("minJavaVersionForTests") as? JavaVersion })
  )
  taskExtension.maxJavaVersionForTests.convention(projectExtension.maxJavaVersionForTests
    .orElse(providers.provider { project.findProperty("${name}MaxJavaVersionForTests") as? JavaVersion })
    .orElse(providers.provider { project.findProperty("maxJavaVersionForTests") as? JavaVersion })
  )
  taskExtension.forceJdk.convention(projectExtension.forceJdk
    .orElse(providers.provider {
      @Suppress("UNCHECKED_CAST")
      project.findProperty("forceJdk") as? List<String> ?: emptyList()
    })
  )
  taskExtension.excludeJdk.convention(projectExtension.excludeJdk
    .orElse(providers.provider {
      @Suppress("UNCHECKED_CAST")
      project.findProperty("excludeJdk") as? List<String> ?: emptyList()
    })
  )
  taskExtension.allowReflectiveAccessToJdk.convention(projectExtension.allowReflectiveAccessToJdk
    .orElse(providers.provider { project.findProperty("allowReflectiveAccessToJdk") as? Boolean })
  )
}
