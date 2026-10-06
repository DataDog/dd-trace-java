package datadog.gradle.plugin.muzzle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MuzzleReportConfigurationCacheTest : MuzzlePluginTestFixture() {
  @Test
  fun `dependency report refreshes version ranges with configuration cache reuse`() {
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
          versions = "[1.0.0,)"
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
      "--stacktrace"
    )
    val env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepo.repoUrl)

    val first = run(*args, env = env)
    assertThat(first.task(args[0])?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.output).contains("Configuration cache entry stored")

    val report = file("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv")
    assertThat(report.readText()).isEqualTo(
      "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
        "test-instrumentation,com.example,demo,1.0.0,1.0.0\n"
    )

    mavenRepo.publishVersions("com.example", "demo", listOf("2.0.0"))

    val reused = run(*args, env = env)
    assertThat(reused.task(args[0])?.outcome).describedAs(reused.output).isEqualTo(SUCCESS)
    assertThat(reused.output).contains("Reusing configuration cache")

    assertThat(report.readText())
      .isEqualTo(
        "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
          "test-instrumentation,com.example,demo,1.0.0,2.0.0\n"
      )
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `merge reflects changed reports with configuration cache reuse`(rerunTasks: Boolean) {
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
    listOf("first", "second", "third").forEach { producer ->
      addSubproject("dd-java-agent:instrumentation:$producer",
        """
        plugins {
          id("java")
          id("dd-trace-java.muzzle")
        }
        """
      )
    }
    writeRootProject("""layout.buildDirectory.set(layout.projectDirectory.dir("relocated/build"))""")
    writeFile("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_first.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      first-instrumentation,com.example,demo,1.0.0,2.0.0
      """
    )
    writeFile("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_second.csv",
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
      "--stacktrace"
    )

    val first = run(*args)
    assertThat(first.task(args[0])?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.task(":dd-java-agent:instrumentation:compileMuzzle")).isNull()
    assertThat(first.task(":dd-java-agent:instrumentation:demo:generateMuzzleReport")).isNull()
    assertThat(first.output).contains("Configuration cache entry stored")

    val reused = run(*args)
    assertThat(reused.task(args[0])?.outcome).describedAs(reused.output).isEqualTo(UP_TO_DATE)
    assertThat(reused.output).contains("Reusing configuration cache")

    val report = file("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation.csv")
    assertThat(report.readText())
      .isEqualTo(
        "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
          "first-instrumentation,com.example,demo,1.0.0,3.0.0\n" +
          "second-instrumentation,com.example,other,3.0.0,4.0.0\n"
      )

    writeFile("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_second.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      first-instrumentation,com.example,demo,1.5.0,2.0.0
      """
    )

    val refreshArgs = if (rerunTasks) args + "--rerun-tasks" else args
    val changed = run(*refreshArgs)
    assertThat(changed.task(args[0])?.outcome).describedAs(changed.output).isEqualTo(SUCCESS)
    assertThat(changed.output).contains("Reusing configuration cache")
    assertThat(report.readText()).isEqualTo(
      "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
        "first-instrumentation,com.example,demo,1.0.0,2.0.0\n"
    )

    writeFile("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_third.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      third-instrumentation,com.example,new,4.0.0,5.0.0
      """
    )
    val added = run(*refreshArgs)
    assertThat(added.task(args[0])?.outcome).describedAs(added.output).isEqualTo(SUCCESS)
    assertThat(added.output).contains("Reusing configuration cache")
    assertThat(report.readText()).isEqualTo(
      "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
        "first-instrumentation,com.example,demo,1.0.0,2.0.0\n" +
        "third-instrumentation,com.example,new,4.0.0,5.0.0\n"
    )

    assertThat(file("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_first.csv").delete()).isTrue()
    assertThat(file("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_second.csv").delete()).isTrue()
    val removed = run(*refreshArgs)
    assertThat(removed.task(args[0])?.outcome).describedAs(removed.output).isEqualTo(SUCCESS)
    assertThat(removed.output).contains("Reusing configuration cache")
    assertThat(report.readText()).isEqualTo(
      "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
        "third-instrumentation,com.example,new,4.0.0,5.0.0\n"
    )
  }

  @Test
  fun `merge ignores reports from removed and renamed producers`() {
    val producerScript = """
      plugins {
        id("java")
        id("dd-trace-java.muzzle")
      }
    """
    writeProject(producerScript)
    addSubproject("dd-java-agent:instrumentation", producerScript)
    val currentSettings = file("settings.gradle.kts").readText()
    addSubproject("dd-java-agent:instrumentation:deleted", producerScript)
    writeRootProject("""layout.buildDirectory.set(layout.projectDirectory.dir("relocated/build"))""")

    val active = writeFile("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      active-instrumentation,com.example,demo,1.0.0,2.0.0
      """
    )
    val orphan = writeFile("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_deleted.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      deleted-instrumentation,com.example,deleted,3.0.0,4.0.0
      """
    )
    val args = arrayOf(
      ":dd-java-agent:instrumentation:mergeMuzzleReports",
      "--configuration-cache",
      "--configuration-cache-problems=fail",
      "--stacktrace"
    )
    val report = file("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation.csv")

    val first = run(*args)
    assertThat(first.task(args[0])?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(report.readText()).isEqualTo(
      "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n" +
        "active-instrumentation,com.example,demo,1.0.0,2.0.0\n" +
        "deleted-instrumentation,com.example,deleted,3.0.0,4.0.0\n"
    )

    writeSettings(currentSettings)
    val removed = run(*args)
    assertThat(removed.task(args[0])?.outcome).describedAs(removed.output).isEqualTo(SUCCESS)
    assertThat(report.readText()).isEqualTo(active.readText())
    assertThat(orphan).exists()

    writeSettings(currentSettings.replace(":instrumentation:demo", ":instrumentation:renamed"))
    writeFile("dd-java-agent/instrumentation/renamed/build.gradle.kts", producerScript)
    val renamed = writeFile("relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_renamed.csv",
      """
      instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion
      renamed-instrumentation,com.example,demo,1.5.0,1.5.0
      """
    )
    val refreshed = run(*args)
    assertThat(refreshed.task(args[0])?.outcome).describedAs(refreshed.output).isEqualTo(SUCCESS)
    assertThat(report.readText()).isEqualTo(renamed.readText())
    assertThat(active).exists()
    assertThat(orphan).exists()

    val reused = run(*args)
    assertThat(reused.task(args[0])?.outcome).describedAs(reused.output).isEqualTo(UP_TO_DATE)
    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(report.readText()).isEqualTo(renamed.readText())
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
