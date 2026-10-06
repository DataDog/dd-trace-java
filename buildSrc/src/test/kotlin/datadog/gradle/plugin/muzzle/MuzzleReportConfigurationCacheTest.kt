package datadog.gradle.plugin.muzzle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test

class MuzzleReportConfigurationCacheTest : MuzzlePluginTestFixture() {
  @Test
  fun `dependency report executes with configuration cache reuse`() {
    val mavenRepo = createMavenRepoFixture()
    mavenRepo.publishVersions("com.example", "demo", listOf("1.0.0"))

    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      dependencies {
        runtimeOnly(project(":dd-java-agent:agent-tooling"))
      }

      muzzle {
        pass {
          group = "com.example"
          module = "demo"
          versions = "1.0.0"
        }
      }
      """
    )
    writeNoopScanPlugin()
    writeRootProject("""layout.buildDirectory.set(layout.projectDirectory.dir("relocated/build"))""")

    val args = arrayOf(
      ":dd-java-agent:instrumentation:demo:generateMuzzleReport",
      "--configuration-cache",
      "--configuration-cache-problems=fail",
      "--rerun-tasks",
      "--stacktrace"
    )
    val env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepo.repoUrl)

    val first = run(*args, env = env)
    assertThat(first.task(args[0])?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.output).contains("Configuration cache entry stored")

    val reused = run(*args, env = env)
    assertThat(reused.task(args[0])?.outcome).describedAs(reused.output).isEqualTo(SUCCESS)
    assertThat(reused.output).contains("Reusing configuration cache")

    assertThat(file("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv").readText())
      .isEqualTo(
        "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
          "test-instrumentation,com.example,demo,1.0.0,1.0.0\n"
      )
  }

  @Test
  fun `merge combines generated version ranges with configuration cache reuse`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      """
    )
    addSubproject("dd-java-agent:instrumentation",
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
      """
    )
    writeRootProject("""layout.buildDirectory.set(layout.projectDirectory.dir("relocated/build"))""")
    writeFile("relocated/build/muzzle-deps-results/first.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      first-instrumentation,com.example,demo,1.0.0,2.0.0
      """
    )
    writeFile("relocated/build/muzzle-deps-results/second.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      first-instrumentation,com.example,demo,1.5.0,3.0.0
      second-instrumentation,com.example,other,3.0.0,4.0.0
      """
    )

    val args = arrayOf(
      ":dd-java-agent:instrumentation:mergeMuzzleReports",
      "--configuration-cache",
      "--configuration-cache-problems=fail",
      "--rerun-tasks",
      "--stacktrace"
    )

    val first = run(*args)
    assertThat(first.task(args[0])?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.task(":dd-java-agent:instrumentation:compileMuzzle")).isNull()
    assertThat(first.task(":dd-java-agent:instrumentation:demo:generateMuzzleReport")).isNull()
    assertThat(first.output).contains("Configuration cache entry stored")

    val reused = run(*args)
    assertThat(reused.task(args[0])?.outcome).describedAs(reused.output).isEqualTo(SUCCESS)
    assertThat(reused.output).contains("Reusing configuration cache")

    assertThat(file("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation.csv").readText())
      .isEqualTo(
        "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
          "first-instrumentation,com.example,demo,1.0.0,3.0.0\n" +
          "second-instrumentation,com.example,other,3.0.0,4.0.0\n"
      )
  }

  @Test
  fun `legacy generation keeps compilation prerequisites and reuses configuration cache`() {
    writeProject(
      """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }

      sourceSets.create("main_java11")
      """
    )

    val args = arrayOf(
      ":dd-java-agent:instrumentation:demo:generateMuzzleReport",
      "--configuration-cache",
      "--configuration-cache-problems=fail",
      "--rerun-tasks",
      "--stacktrace"
    )

    val first = run(*args)
    assertThat(first.task(args[0])?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.task(":dd-java-agent:agent-bootstrap:compileMain_java11Java")).isNotNull()
    assertThat(first.task(":dd-java-agent:instrumentation:demo:compileMain_java11Java")).isNotNull()
    assertThat(first.output).contains("Configuration cache entry stored")

    val reused = run(*args)
    assertThat(reused.task(args[0])?.outcome).describedAs(reused.output).isEqualTo(SUCCESS)
    assertThat(reused.output).contains("Reusing configuration cache")
  }
}
