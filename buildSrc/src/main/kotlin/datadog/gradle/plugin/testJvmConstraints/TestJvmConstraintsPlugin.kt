package datadog.gradle.plugin.testJvmConstraints

import datadog.gradle.plugin.isLinuxArm64
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension.Companion.TEST_JVM_CONSTRAINTS
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

class TestJvmConstraintsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply(JavaPlugin::class.java)

    val projectExtension = project.extensions.create<TestJvmConstraintsExtension>(TEST_JVM_CONSTRAINTS)
    val testJvmSpec = TestJvmSpec(project)

    project.tasks.withType<Test>().configureEach {
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
      inputs.property("$TEST_JVM_CONSTRAINTS.nativeImageCapable", taskExtension.nativeImageCapable).optional(true)

      extensions.add(TEST_JVM_CONSTRAINTS, taskExtension)

      configureTestJvm(testJvmSpec, taskExtension)
    }

    // Jacoco plugin is not applied on every project
    project.pluginManager.withPlugin("org.gradle.jacoco") {
      project.tasks.withType<Test>().configureEach {
        configureJacocoForAdditionalTestJvm(
          testJvmSpec.javaTestLauncher.isPresent,
          project.rootProject.providers.gradleProperty("checkCoverage").isPresent
        )
      }
    }
  }

  /**
   * Configure the jvm launcher of the test task and ensure the test task can be run with the
   * test task launcher.
   */
  private fun Test.configureTestJvm(
    testJvmSpec: TestJvmSpec,
    extension: TestJvmConstraintsExtension
  ) {
    if (testJvmSpec.javaTestLauncher.isPresent) {
      javaLauncher.set(testJvmSpec.javaTestLauncher)
      onlyIf("Test JDK is allowed or forced JDK") {
        extension.isTestJvmAllowed(testJvmSpec)
      }
      onlyIf("Test JDK is native-image capable") {
        extension.isNativeImageCapableTestJvm(testJvmSpec.javaTestLauncher.get())
      }
    } else {
      onlyIf("Current Daemon JVM within allowed version range") {
        extension.isJavaVersionAllowed(JavaVersion.current())
      }
      onlyIf("Current Daemon JVM is native-image capable") {
        extension.isNativeImageCapableDaemon()
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

    // Fix for Linux arm64 ByteBuddy error:
    // "Could not self-attach to current VM using external process"
    if (project.isLinuxArm64()) {
      conditionalJvmArgs(
        JavaVersion.VERSION_1_9,
        listOf("-Djdk.attach.allowAttachSelf=true")
      )
    }
  }

  /**
   * Provide arguments if condition is met.
   */
  private fun Test.conditionalJvmArgs(
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
   * Configures the convention, this tells Gradle where to look for values.
   *
   * Currently, the extension is still configured to look at project's _extra_ properties.
   */
  private fun Test.configureConventions(
    taskExtension: TestJvmConstraintsExtension,
    projectExtension: TestJvmConstraintsExtension
  ) {
    taskExtension.minJavaVersion.convention(projectExtension.minJavaVersion
      .orElse(project.providers.provider { project.findProperty("${name}MinJavaVersionForTests") as? JavaVersion })
      .orElse(project.providers.provider { project.findProperty("minJavaVersion") as? JavaVersion })
    )
    taskExtension.maxJavaVersion.convention(projectExtension.maxJavaVersion
      .orElse(project.providers.provider { project.findProperty("${name}MaxJavaVersionForTests") as? JavaVersion })
      .orElse(project.providers.provider { project.findProperty("maxJavaVersion") as? JavaVersion })
    )
    taskExtension.forceJdk.convention(projectExtension.forceJdk
      .orElse(project.providers.provider {
        @Suppress("UNCHECKED_CAST")
        project.findProperty("forceJdk") as? List<String> ?: emptyList()
      })
    )
    taskExtension.excludeJdk.convention(projectExtension.excludeJdk
      .orElse(project.providers.provider {
        @Suppress("UNCHECKED_CAST")
        project.findProperty("excludeJdk") as? List<String> ?: emptyList()
      })
    )
    taskExtension.allowReflectiveAccessToJdk.convention(projectExtension.allowReflectiveAccessToJdk
      .orElse(project.providers.provider { project.findProperty("allowReflectiveAccessToJdk") as? Boolean })
    )
    taskExtension.nativeImageCapable.convention(projectExtension.nativeImageCapable)
  }
}

internal fun Test.configureJacocoForAdditionalTestJvm(
  hasAdditionalTestJvmLauncher: Boolean,
  checkCoverage: Boolean
) {
  // Disable jacoco for additional 'testJvm' tests unless coverage was explicitly requested.
  if (hasAdditionalTestJvmLauncher && !checkCoverage) {
    extensions.configure<JacocoTaskExtension> {
      isEnabled = false
    }
  }
}
