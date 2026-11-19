package datadog.gradle.plugin.testJvmConstraints

import datadog.gradle.plugin.HostPlatform
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension.Companion.TEST_JVM_CONSTRAINTS
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

class TestJvmConstraintsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    if (project.extensions.findByName(TEST_JVM_CONSTRAINTS) != null) {
      return
    }

    project.pluginManager.apply(JavaPlugin::class.java)

    val projectExtension = project.extensions.create<TestJvmConstraintsExtension>(TEST_JVM_CONSTRAINTS)
    val testJvmSpec = TestJvmSpec(project)

    project.tasks.withType<Test>().configureEach {
      if (extensions.findByName(TEST_JVM_CONSTRAINTS) != null) {
        return@configureEach
      }

      inputs.property("testJvm", testJvmSpec.testJvmProperty).optional(true)
      // Gradle tracks the language version, but not the resolved vendor or full JVM versions.
      inputs.property(
        "jvmIdentity",
        javaLauncher.map { launcher ->
          with(launcher.metadata) {
            mapOf(
              "languageVersion" to languageVersion.asInt().toString(),
              "vendor" to vendor,
              "runtimeVersion" to javaRuntimeVersion,
              "vmVersion" to jvmVersion,
              "operatingSystem" to System.getProperty("os.name"),
              "architecture" to System.getProperty("os.arch"),
            )
          }
        }
      )

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
      // The coverage test JVM comes from the test JVM constraints, which are final once evaluated
      project.afterEvaluate {
        val checksCoverage = project.checksCoverage(
          project.rootProject.providers.gradleProperty(CHECK_COVERAGE).isPresent,
          project.rootProject.providers.gradleProperty(CHECK_COVERAGE_ON_FIRST_TEST_JVM).isPresent,
          testJvmSpec.testJvmProperty.orNull,
        )
        project.tasks.withType<Test>().configureEach {
          configureJacocoForAdditionalTestJvm(
            testJvmSpec.javaTestLauncherOverride.isPresent,
            checksCoverage
          )
        }
      }
    }

    registerCheckTask(project)
  }

  /**
   * Registers the [CheckTestJvmConstraints] task, run by `check`.
   */
  private fun registerCheckTask(project: Project) {
    val checkTask = project.tasks.register<CheckTestJvmConstraints>(CheckTestJvmConstraints.TASK_NAME) {
      group = LifecycleBasePlugin.VERIFICATION_GROUP
      description = "Checks that no test task has unsatisfiable test JVM constraints."
      testTasks.addAll(project.provider { testTaskConstraints(project) })
      coverageTestJvmProblem.set(project.provider { project.coverageTestJvmProblem() })
    }
    project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
      dependsOn(checkTask)
    }
  }

  private fun testTaskConstraints(project: Project): List<CheckTestJvmConstraints.TestTaskConstraints> {
    val testTasks = project.tasks.withType<Test>()
    // Read the names first: configuring a test task may register other tasks.
    return testTasks.names.map { name ->
      val test = testTasks.getByName(name)
      val constraints = test.extensions.getByType<TestJvmConstraintsExtension>()
      project.objects.newInstance<CheckTestJvmConstraints.TestTaskConstraints>().apply {
        taskPath.set(test.path)
        minJavaVersion.set(constraints.minJavaVersion)
        maxJavaVersion.set(constraints.maxJavaVersion)
        includeJdk.set(constraints.includeJdk)
        excludeJdk.set(constraints.excludeJdk)
        forceJdk.set(constraints.forceJdk)
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
    // If `testJvm` is present
    if (testJvmSpec.requestedTestJvmLauncher.isPresent) {
      // Only set Test.javaLauncher when required, i.e. when override is present,
      // it is redundant when `testJvm` and the project toolchain both select the Gradle daemon JVM.
      if (testJvmSpec.javaTestLauncherOverride.isPresent) {
        javaLauncher.set(testJvmSpec.javaTestLauncherOverride)
      }
      // Constraints still use the explicit testJvm selection when no override is needed.
      onlyIf("Test JDK is allowed or forced JDK") {
        extension.isTestJvmAllowed(testJvmSpec)
      }
      onlyIf("Test JDK is native-image capable") {
        extension.isNativeImageCapableTestJvm(testJvmSpec.requestedTestJvmLauncher.get())
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
    if (HostPlatform.isLinuxArm64()) {
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

  private fun configureConventions(
    taskExtension: TestJvmConstraintsExtension,
    projectExtension: TestJvmConstraintsExtension
  ) {
    taskExtension.minJavaVersion.convention(projectExtension.minJavaVersion)
    taskExtension.maxJavaVersion.convention(projectExtension.maxJavaVersion)
    taskExtension.forceJdk.convention(projectExtension.forceJdk)
    taskExtension.includeJdk.convention(projectExtension.includeJdk)
    taskExtension.excludeJdk.convention(projectExtension.excludeJdk)
    taskExtension.allowReflectiveAccessToJdk.convention(projectExtension.allowReflectiveAccessToJdk)
    taskExtension.nativeImageCapable.convention(projectExtension.nativeImageCapable)
    taskExtension.coverageTestJvm.convention(projectExtension.coverageTestJvm)
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
