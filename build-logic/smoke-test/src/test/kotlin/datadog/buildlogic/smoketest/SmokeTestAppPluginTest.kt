package datadog.buildlogic.smoketest

import datadog.buildlogic.smoketest.NestedGradleBuild.Companion.gradleExecutableName
import datadog.buildlogic.smoketest.NestedMavenBuild.Companion.mavenWrapperName
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.plugins.JavaPlugin
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

/**
 * Fast in-process tests that exercise plugin application and extension wiring through
 * [ProjectBuilder]. End-to-end task execution lives in [SmokeTestAppEndToEndTest].
 */
class SmokeTestAppPluginTest {

  @Test
  fun `applying the plugin creates the smokeTestApp extension`() {
    val project = ProjectBuilder.builder().build()

    project.plugins.apply("dd-trace-java.smoke-test-app")

    assertThat(project.extensions.findByType<SmokeTestAppExtension>()).isNotNull
  }

  @Test
  fun `plugin is a no-op when no smokeTestApp application is configured`() {
    val project = ProjectBuilder.builder().build()

    project.plugins.apply("dd-trace-java.smoke-test-app")

    // No nested build task should be registered until `gradleApp { }` or `mavenApp { }` is invoked.
    assertThat(project.tasks.withType<NestedGradleBuild>()).isEmpty()
    assertThat(project.tasks.withType<NestedMavenBuild>()).isEmpty()
  }

  @Test
  fun `extension defaults applicationDir to projectDir slash application`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.applicationDir.get().asFile)
      .isEqualTo(project.layout.projectDirectory.dir("application").asFile)
  }

  @Test
  fun `extension defaults applicationBuildDir to buildDir slash application`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.applicationBuildDir.get().asFile)
      .isEqualTo(project.layout.buildDirectory.dir("application").get().asFile)
  }

  @Test
  fun `extension defaults gradleVersion to the smoke-test pinned version`() {
    val project = ProjectBuilder.builder().build()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.gradleVersion.get()).isEqualTo(DEFAULT_NESTED_GRADLE_VERSION)
  }

  @Test
  fun `gradleApp block registers NestedGradleBuild task with configured inputs`() {
    val project = ProjectBuilder.builder().build()
    project.apply<JavaPlugin>()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()
    extension.gradleVersion.set("8.14.5")
    extension.gradleDistributionBaseUrl.set("https://mass.example")
    extension.gradleApp {
      taskName.set("packageApp")
      artifactPath.set("libs/test.jar")
      sysProperty.set("test.path")
      nestedTasks.set(listOf("assemble", "check"))
      buildArguments.add("-Ddemo=true")
      environment.put("DEMO_ENV", "true")
      buildCacheEnabled.set(true)
    }

    val task = project.tasks.getByName("packageApp") as NestedGradleBuild

    assertThat(task.applicationDir.get().asFile)
      .isEqualTo(project.layout.projectDirectory.dir("application").asFile)
    assertThat(task.applicationBuildDir.get().asFile)
      .isEqualTo(project.layout.buildDirectory.dir("application").get().asFile)
    assertThat(task.gradleVersion.get()).isEqualTo("8.14.5")
    assertThat(task.gradleDistributionBaseUrl.get()).isEqualTo("https://mass.example")
    assertThat(task.tasksToRun.get()).containsExactly("assemble", "check")
    assertThat(task.buildArguments.get()).containsExactly("-Ddemo=true")
    assertThat(task.environment.get()).containsEntry("DEMO_ENV", "true")
    assertThat(task.buildCacheEnabled.get()).isTrue()
    assertThat(task.stopTimeoutSeconds.isPresent).isFalse()
  }

  @Test
  fun `mavenApp block registers NestedMavenBuild task with configured inputs`() {
    val project = ProjectBuilder.builder().build()
    val wrapperProperties = project.file(".mvn/wrapper/maven-wrapper.properties")
    wrapperProperties.parentFile.mkdirs()
    wrapperProperties.writeText("distributionUrl=https://repo.example/maven2/test.zip")
    project.apply<JavaPlugin>()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()
    extension.mavenApp {
      taskName.set("packageApp")
      artifactPath.set("target/test.jar")
      sysProperty.set("test.path")
      goals.set(listOf("verify"))
      arguments.add("-Ddemo=true")
      environment.put("DEMO_ENV", "true")
      mavenOpts.set("-Xmx512M")
      mavenExecutable.set(project.layout.projectDirectory.file("mvnw"))
      mavenRepositoryProxy.set("https://repo.example")
      useMavenLocalRepository.set(true)
      mavenLocalRepository.set(project.layout.projectDirectory.dir("m2"))
    }

    val task = project.tasks.getByName("packageApp") as NestedMavenBuild

    assertThat(task.applicationDir.get().asFile)
      .isEqualTo(project.layout.projectDirectory.dir("application").asFile)
    assertThat(task.applicationBuildDir.get().asFile)
      .isEqualTo(project.layout.buildDirectory.dir("application").get().asFile)
    assertThat(task.goals.get()).containsExactly("verify")
    assertThat(task.arguments.get()).containsExactly("-Ddemo=true")
    assertThat(task.environment.get()).containsEntry("DEMO_ENV", "true")
    assertThat(task.mavenOpts.get()).isEqualTo("-Xmx512M")
    assertThat(task.mavenExecutable.get().asFile)
      .isEqualTo(project.layout.projectDirectory.file("mvnw").asFile)
    assertThat(task.mavenWrapperFiles.files).containsExactly(wrapperProperties)
    assertThat(task.mavenRepositoryProxy.get()).isEqualTo("https://repo.example")
    assertThat(task.useMavenLocalRepository.get()).isTrue()
    assertThat(task.mavenLocalRepository.get().asFile)
      .isEqualTo(project.layout.projectDirectory.dir("m2").asFile)
    assertThat(task.buildTimeoutSeconds.isPresent).isFalse()
  }

  @Test
  fun `nested build timeouts can be overridden`() {
    val project = ProjectBuilder.builder().build()
    project.apply<JavaPlugin>()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()
    extension.gradleApp {
      taskName.set("packageGradleApp")
      artifactPath.set("libs/test.jar")
      sysProperty.set("test.gradle.path")
      stopTimeoutSeconds.set(45L)
    }
    extension.mavenApp {
      taskName.set("packageMavenApp")
      artifactPath.set("target/test.jar")
      sysProperty.set("test.maven.path")
      buildTimeoutSeconds.set(60L)
    }

    val gradleTask = project.tasks.getByName("packageGradleApp") as NestedGradleBuild
    val mavenTask = project.tasks.getByName("packageMavenApp") as NestedMavenBuild

    assertThat(gradleTask.stopTimeoutSeconds.get()).isEqualTo(45L)
    assertThat(mavenTask.buildTimeoutSeconds.get()).isEqualTo(60L)
  }

  @Test
  fun `wrapper executable names follow the host operating system`() {
    assertThat(mavenWrapperName("Windows 11")).isEqualTo("mvnw.cmd")
    assertThat(mavenWrapperName("Mac OS X")).isEqualTo("mvnw")
    assertThat(gradleExecutableName("Windows Server 2022")).isEqualTo("gradle.bat")
    assertThat(gradleExecutableName("Linux")).isEqualTo("gradle")
  }

  @Test
  fun `manual NestedGradleBuild task receives smokeTestApp conventions`() {
    val project = ProjectBuilder.builder().build()
    project.apply<JavaPlugin>()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()
    extension.initScripts.set(listOf("init-script"))
    extension.gradleProperties.set(
      mapOf("mavenRepositoryProxy" to "https://repo.example"),
    )

    val task = project.tasks.register("customBuild", NestedGradleBuild::class.java) {
      applicationDir.set(project.layout.projectDirectory.dir("application"))
      applicationBuildDir.set(project.layout.buildDirectory.dir("application"))
      tasksToRun.set(listOf("buildJar"))
    }.get()

    assertThat(task.initScripts.get()).containsExactly("init-script")
    assertThat(task.gradleProperties.get())
      .containsEntry("mavenRepositoryProxy", "https://repo.example")
  }

  @Test
  fun `Gradle distribution URI routes through MASS artifact path`() {
    assertThat(gradleDistributionUri("https://mass.example", "8.14.5").toString())
      .isEqualTo(
        "https://mass.example/internal/artifact/services.gradle.org/distributions/gradle-8.14.5-bin.zip",
      )
    assertThat(gradleDistributionUri("https://mass.example/", "8.14.5").toString())
      .isEqualTo(
        "https://mass.example/internal/artifact/services.gradle.org/distributions/gradle-8.14.5-bin.zip",
      )
  }

  @Test
  fun `extension defaults javaLauncher to a JDK 21 toolchain`() {
    // JavaToolchainService is contributed by the `java-base` plugin; apply something that
    // pulls it in so ProjectBuilder can resolve the convention.
    val project = ProjectBuilder.builder().build()
    project.apply<JavaPlugin>()
    project.plugins.apply("dd-trace-java.smoke-test-app")

    val extension = project.extensions.getByType<SmokeTestAppExtension>()

    assertThat(extension.javaLauncher.get().metadata.languageVersion)
      .isEqualTo(JavaLanguageVersion.of(DEFAULT_NESTED_JAVA_VERSION))
  }
}
