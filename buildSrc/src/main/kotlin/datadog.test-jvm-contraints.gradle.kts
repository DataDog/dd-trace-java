import datadog.gradle.plugin.testJvmConstraints.ProvideJvmArgsOnJvmLauncherVersion
import datadog.gradle.plugin.testJvmConstraints.TestJvmConstraintsExtension
import datadog.gradle.plugin.testJvmConstraints.TestJvmJavaLauncher
import datadog.gradle.plugin.testJvmConstraints.*
import org.gradle.api.JavaVersion
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.*

plugins {
  java
  jacoco apply false
}

val testJvmJavaLauncher = TestJvmJavaLauncher(project)

tasks.withType<Test>().configureEach {
  if (extensions.findByName(TestJvmConstraintsExtension.NAME) != null) {
    return@configureEach
  }

  inputs.property("testJvm", providers.gradleProperty("testJvm"))

  val extension = project.objects.newInstance<TestJvmConstraintsExtension>(name, project.objects, project.providers, project)
  inputs.property("${TestJvmConstraintsExtension.NAME}.allowReflectiveAccessToJdk", extension.allowReflectiveAccessToJdk)
  inputs.property("${TestJvmConstraintsExtension.NAME}.excludeJdk", extension.excludeJdk)
  inputs.property("${TestJvmConstraintsExtension.NAME}.forceJdk", extension.forceJdk)
  inputs.property("${TestJvmConstraintsExtension.NAME}.minJavaVersionForTests", extension.minJavaVersionForTests)
  inputs.property("${TestJvmConstraintsExtension.NAME}.maxJavaVersionForTests", extension.maxJavaVersionForTests)

  extensions.add(TestJvmConstraintsExtension.NAME, extension)

  configureTestJvm(extension)
}

// TODO make this part of the testJvm test task extension
fun Test.configureJvmArgs(
  applyFromVersion: JavaVersion,
  jvmArgsToApply: List<String>,
  additionalCondition: Provider<Boolean>? = null
) {
  jvmArgumentProviders.add(
    ProvideJvmArgsOnJvmLauncherVersion(
      this,
      applyFromVersion,
      jvmArgsToApply,
      additionalCondition ?: project.providers.provider { true }
    )
  )
}

fun Test.configureTestJvm(extension: TestJvmConstraintsExtension) {
  if (testJvmJavaLauncher.javaTestLauncher.isPresent) {
    javaLauncher = testJvmJavaLauncher.javaTestLauncher
    onlyIf("Allowed or forced JDK") {
      !extension.isJdkExcluded(testJvmJavaLauncher.normalizedTestJvm.get()) &&
        (extension.isJavaLauncherAllowed(testJvmJavaLauncher.javaTestLauncher.get()) ||
          extension.isJdkForced(testJvmJavaLauncher.normalizedTestJvm.get()))
    }

    // TODO refactor out ?
    // Disable jacoco for additional 'testJvm' tests to speed things up a bit
    extensions.configure<JacocoTaskExtension> {
      val hasCoverage: Boolean by project.extra
      // TODO read enabled ?
      if (hasCoverage) {
        isEnabled = false
      }
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

