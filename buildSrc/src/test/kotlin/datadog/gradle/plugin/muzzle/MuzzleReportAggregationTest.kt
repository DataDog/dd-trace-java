package datadog.gradle.plugin.muzzle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test

class MuzzleReportAggregationTest : MuzzlePluginTestFixture() {
  @Test
  fun `help distinguishes compatibility validation from dependency reports`() {
    writeReportingProject("muzzle { pass { coreJdk() } }")
    val descriptions = mapOf(
      "demo:muzzle" to "Check instrumentation compatibility against the configured dependency versions",
      "demo:generateMuzzleReport" to "Generate this instrumentation's dependency range report",
      "aggregateMuzzleReports" to "Aggregate instrumentation dependency range reports",
      "mergeMuzzleReports" to "Deprecated: use aggregateMuzzleReports for dependency range reports"
    )
    descriptions.forEach { (task, description) ->
      val result = run(
        "help", "--task", ":dd-java-agent:instrumentation:$task",
        "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace"
      )
      assertThat(result.task(":help")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      assertThat(result.output).contains(description)
      assertThat(result.tasks).noneMatch { it.path.contains("muzzle", ignoreCase = true) }
    }
    assertThat(file(REPORT)).doesNotExist()
  }

  @Test
  fun `aggregates only published reports and refreshes Maven ranges with configuration cache reuse`() {
    val repo = createMavenRepoFixture()
    repo.publishVersions("example", "library", listOf("1.0", "1.10", "2.0", "3.0"))
    writeReportingProject(
      """
      muzzle {
        pass {
          name = "shared"
          group = "example"
          module = "library"
          versions = "[1,2)"
        }
        fail {
          name = "shared"
          group = "example"
          module = "library"
          versions = "[2,3)"
        }
        pass { coreJdk() }
        pass {
          group = "missing"
          module = "skipped"
          versions = "[1,)"
          skipFromReport = true
        }
      }
      """
    )
    addAggregationInput("dd-java-agent:instrumentation:nested:other", producerScript(
      """
      muzzle {
        pass {
          name = "shared"
          group = "example"
          module = "library"
          versions = "[3,)"
        }
        pass {
          name = "alpha"
          group = "example"
          module = "library"
          versions = "[1,2)"
        }
      }
      """
    ))
    addAggregationInput("dd-java-agent:instrumentation:empty", producerScript("muzzle { pass { coreJdk() } }"))
    addAggregationInput("dd-java-agent:instrumentation:stubs", """plugins { id("java") }""")
    addSubproject("dd-java-agent:instrumentation:unselected", producerScript("muzzle { pass { coreJdk() } }"))
    writeFile("relocated/build/muzzle-deps-results/stale.csv", HEADER + "stale,example,library,0,99")
    writeFile(REPORT, HEADER + "previous,example,library,0,99")

    val args = arrayOf(
      ":dd-java-agent:instrumentation:aggregateMuzzleReports",
      "--configuration-cache", "--configuration-cache-problems=fail", "--build-cache", "--stacktrace"
    )
    val env = mapOf("MAVEN_REPOSITORY_PROXY" to repo.repoUrl)
    val first = run(*args, env = env)
    assertThat(first.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.output).contains("Configuration cache entry stored")
    assertThat(file(REPORT).readText()).isEqualTo(
      HEADER + "alpha,example,library,1.0,1.10\nshared,example,library,1.0,3.0\n"
    )
    assertThat(file("dd-java-agent/instrumentation/demo/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv").readText())
      .isEqualTo(HEADER + "shared,example,library,1.0,2.0\n")
    assertThat(file("dd-java-agent/instrumentation/empty/build/muzzle-deps-results/dd-java-agent_instrumentation_empty.csv").readText())
      .isEqualTo(HEADER)
    assertThat(first.tasks).noneMatch { it.path.contains("muzzle-Assert") }
    assertThat(first.task(":dd-java-agent:instrumentation:demo:compileMuzzle")).isNotNull()
    assertThat(first.tasks).noneMatch { it.path.startsWith(":dd-java-agent:instrumentation:stubs:") }
    assertThat(first.tasks).noneMatch { it.path.startsWith(":dd-java-agent:instrumentation:unselected:") }
    assertThat(first.tasks).noneMatch { it.path == ":aggregateMuzzleReports" || it.path.endsWith(":muzzleInstrumentationReport") }

    val second = run(*args, env = env)
    assertThat(second.output).contains("Reusing configuration cache")
    assertThat(second.task(":dd-java-agent:instrumentation:demo:generateMuzzleReport")?.outcome).isEqualTo(SUCCESS)
    assertThat(second.task(":dd-java-agent:instrumentation:nested:other:generateMuzzleReport")?.outcome).isEqualTo(SUCCESS)
    assertThat(second.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).isEqualTo(SUCCESS)
    assertThat(second.task(":generateMuzzleReport")).isNull()

    repo.publishVersions("example", "library", listOf("4.0"))
    val refreshed = run(*args, env = env)
    assertThat(refreshed.output).contains("Reusing configuration cache")
    assertThat(refreshed.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(refreshed.output).isEqualTo(SUCCESS)
    assertThat(file(REPORT).readText()).isEqualTo(
      HEADER + "alpha,example,library,1.0,1.10\nshared,example,library,1.0,4.0\n"
    )
  }

  @Test
  fun `help does not query Maven and producer failure prevents aggregation`() {
    val repo = createMavenRepoFixture()
    writeReportingProject(
      """
      muzzle {
        pass {
          group = "missing"
          module = "library"
          versions = "[1,)"
        }
      }
      """
    )
    val env = mapOf("MAVEN_REPOSITORY_PROXY" to repo.repoUrl)
    val help = run("help", "--configuration-cache", "--configuration-cache-problems=fail", env = env)
    assertThat(help.task(":help")?.outcome).describedAs(help.output).isEqualTo(SUCCESS)
    assertThat(help.tasks).noneMatch { it.path.contains("Muzzle") || it.path.contains("muzzle") }
    assertThat(file(REPORT)).doesNotExist()

    val failed = run(
      ":dd-java-agent:instrumentation:aggregateMuzzleReports",
      "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace",
      expectFailure = true, env = env
    )
    assertThat(failed.output).contains("BUILD FAILED")
    assertThat(failed.task(":dd-java-agent:instrumentation:demo:generateMuzzleReport")?.outcome).isEqualTo(FAILED)
    assertThat(failed.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")).isNull()
    assertThat(file(REPORT)).doesNotExist()
  }

  @Test
  fun `empty aggregation ignores non reporting projects and emits the CSV header`() {
    writeSettings("""rootProject.name = "empty-reports"""")
    writeAggregationProject()
    addAggregationInput("dd-java-agent:instrumentation:stubs", """plugins { id("java") }""")
    val result = run("aggregateMuzzleReports", "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")
    assertThat(result.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(file(REPORT).readText()).isEqualTo(HEADER)
  }

  @Test
  fun `Maven equivalent versions retain the existing last spelling selection`() {
    writeSettings("""rootProject.name = "equivalent-versions"""")
    writeAggregationProject()
    writeFile("dd-java-agent/instrumentation/build.gradle.kts",
      """
      tasks.named<datadog.gradle.plugin.muzzle.tasks.MuzzleAggregateReportTask>("aggregateMuzzleReports") {
        versionReports.from(layout.projectDirectory.file("versions.csv"))
      }
      """, append = true
    )
    val a = "same,example,library,1,2\n"
    val b = "same,example,library,1.0,2.0\n"
    listOf(a + b, b + a).forEach { rows ->
      writeFile("dd-java-agent/instrumentation/versions.csv", HEADER + rows)
      val result = run("aggregateMuzzleReports", "--configuration-cache", "--configuration-cache-problems=fail")
      assertThat(result.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      val expected = if (rows == a + b) "same,example,library,1.0,2.0\n" else "same,example,library,1,2\n"
      assertThat(file(REPORT).readText()).isEqualTo(HEADER + expected)
    }
  }

  @Test
  fun `module generation stays local even when aggregation is configured`() {
    val repo = createMavenRepoFixture()
    repo.publishVersions("example", "library", listOf("1.0", "2.0"))
    writeReportingProject(
      """
      muzzle {
        pass {
          name = "demo"
          group = "example"
          module = "library"
          versions = "[1,2)"
        }
      }
      """
    )
    addAggregationInput("dd-java-agent:instrumentation:other", producerScript(
      """
      muzzle {
        pass {
          group = "missing"
          module = "library"
          versions = "[1,)"
        }
      }
      """
    ))
    val args = arrayOf(
      ":dd-java-agent:instrumentation:demo:generateMuzzleReport",
      "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace"
    )
    val env = mapOf("MAVEN_REPOSITORY_PROXY" to repo.repoUrl)
    val first = run(*args, env = env)
    assertThat(first.task(args[0])?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.output).contains("Configuration cache entry stored")
    assertThat(first.output).doesNotContain("generateMuzzleReport is deprecated")
    assertThat(first.task(":dd-java-agent:instrumentation:demo:compileMuzzle")).isNotNull()
    assertThat(first.task(":dd-java-agent:agent-bootstrap:compileMain_java11Java")).isNotNull()
    assertThat(first.task(":dd-java-agent:instrumentation:other:generateMuzzleReport")).isNull()
    assertThat(first.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")).isNull()
    assertThat(file(REPORT)).doesNotExist()
    assertThat(file("dd-java-agent/instrumentation/relocated/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv"))
      .doesNotExist()
    val expected = HEADER + "demo,example,library,1.0,1.0\n"
    assertThat(file("dd-java-agent/instrumentation/demo/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv").readText())
      .isEqualTo(expected)
    val reused = run(*args, env = env)
    assertThat(reused.task(args[0])?.outcome).describedAs(reused.output).isEqualTo(SUCCESS)
    assertThat(reused.output).contains("Reusing configuration cache")
    assertThat(file("dd-java-agent/instrumentation/demo/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv").readText())
      .isEqualTo(expected)
    assertThat(file(REPORT)).doesNotExist()
  }

  @Test
  fun `parent merge command delegates to aggregation`() {
    writeReportingProject("muzzle { pass { coreJdk() } }")
    val result = run(
      ":dd-java-agent:instrumentation:mergeMuzzleReports",
      "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace"
    )
    assertThat(result.task(":dd-java-agent:instrumentation:mergeMuzzleReports")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.task(":aggregateMuzzleReports")).isNull()
    assertThat(result.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:generateMuzzleReport")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.output).contains("mergeMuzzleReports is deprecated; use aggregateMuzzleReports")
    assertThat(file(REPORT).readText()).isEqualTo(HEADER)
    assertThat(file("dd-java-agent/instrumentation/relocated/build/muzzle-deps-results/legacy-parent.csv"))
      .doesNotExist()
  }

  @Test
  fun `changing report artifact order retains legacy version spelling without reusing task output`() {
    writeSettings("""rootProject.name = "ordered-reports"""")
    writeAggregationProject()
    writeFile("dd-java-agent/instrumentation/a.csv", HEADER + "same,example,library,1,2")
    writeFile("dd-java-agent/instrumentation/b.csv", HEADER + "same,example,library,1.0,2.0")
    writeFile("dd-java-agent/instrumentation/build.gradle.kts",
      """
      tasks.named<datadog.gradle.plugin.muzzle.tasks.MuzzleAggregateReportTask>("aggregateMuzzleReports") {
        val reports = if (providers.gradleProperty("reverseReports").isPresent) listOf("b.csv", "a.csv") else listOf("a.csv", "b.csv")
        versionReports.setFrom(reports.map { layout.projectDirectory.file(it) })
      }
      """, append = true
    )
    val args = arrayOf("aggregateMuzzleReports", "--configuration-cache", "--configuration-cache-problems=fail", "--build-cache")
    val first = run(*args)
    assertThat(first.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(file(REPORT).readText()).isEqualTo(HEADER + "same,example,library,1.0,2.0\n")
    val reversed = run(*args, "-PreverseReports")
    assertThat(reversed.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(reversed.output).isEqualTo(SUCCESS)
    assertThat(file(REPORT).readText()).isEqualTo(HEADER + "same,example,library,1,2\n")
  }

  @Test
  fun `task directive overrides determine filtering`() {
    writeReportingProject(
      """
      muzzle {
        pass { group = "missing"; module = "library"; versions = "[1,)" }
      }
      tasks.named<datadog.gradle.plugin.muzzle.tasks.MuzzleGenerateReportTask>("generateMuzzleReport") {
        reportDirectives.set(emptyList())
      }
      """
    )
    val result = run(
      ":dd-java-agent:instrumentation:demo:generateMuzzleReport",
      "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace"
    )
    assertThat(result.task(":dd-java-agent:instrumentation:demo:generateMuzzleReport")?.outcome)
      .describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:compileMuzzle")).isNotNull()
    assertThat(file("dd-java-agent/instrumentation/demo/build/muzzle-deps-results/dd-java-agent_instrumentation_demo.csv").readText())
      .isEqualTo(HEADER)
  }

  @Test
  fun `aggregation and aliases preserve the relocated CI artifact path without a root report task`() {
    writeReportingProject("muzzle { pass { coreJdk() } }")
    val cc = arrayOf("--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")
    val ci = run(":dd-java-agent:instrumentation:aggregateMuzzleReports", *cc)
    assertThat(ci.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(ci.output).isEqualTo(SUCCESS)
    assertThat(ci.task(":generateMuzzleReport")).isNull()
    assertThat(ci.task(":dd-java-agent:instrumentation:muzzleInstrumentationReport")).isNull()
    assertThat(file(REPORT).readText()).isEqualTo(HEADER)

    val missingRootTask = run(":generateMuzzleReport", expectFailure = true)
    assertThat(missingRootTask.output).contains("'generateMuzzleReport' not found in root project")

    listOf("mergeMuzzleReports", "muzzleInstrumentationReport").forEach { alias ->
      val taskPath = ":dd-java-agent:instrumentation:$alias"
      val first = run(taskPath, *cc)
      assertThat(first.task(taskPath)?.outcome).describedAs(first.output).isIn(SUCCESS, UP_TO_DATE)
      assertThat(first.task(":dd-java-agent:instrumentation:demo:generateMuzzleReport")?.outcome).isEqualTo(SUCCESS)
      val reused = run(taskPath, *cc)
      assertThat(reused.output).contains("Reusing configuration cache")
      assertThat(reused.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).isEqualTo(SUCCESS)
      assertThat(reused.task(":aggregateMuzzleReports")).isNull()
    }
  }

  @Test
  fun `aggregation wiring supports isolated projects without the existing validation plugin`() {
    writeSettings("""rootProject.name = "isolated-reports"""")
    writeAggregationProject()
    addAggregationInput("dd-java-agent:instrumentation:stubs", """plugins { id("java") }""")
    addAggregationInput("dd-java-agent:instrumentation:isolated",
      """
      import datadog.gradle.plugin.muzzle.tasks.MuzzleGenerateReportTask
      import org.gradle.api.attributes.Category
      import org.gradle.api.attributes.VerificationType

      plugins { id("java") }
      val report = tasks.register<MuzzleGenerateReportTask>("report")
      configurations.consumable("muzzleReportElements") {
        attributes {
          attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.VERIFICATION))
          attribute(VerificationType.VERIFICATION_TYPE_ATTRIBUTE, objects.named(VerificationType::class.java, "muzzle-dependency-report"))
        }
        outgoing.artifact(report.flatMap { it.versionsFile })
      }
      """
    )
    val args = arrayOf(
      ":dd-java-agent:instrumentation:aggregateMuzzleReports",
      "--isolated-projects", "--configuration-cache-problems=fail", "--stacktrace"
    )
    val first = run(*args)
    assertThat(first.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(first.output).contains("Configuration cache entry stored")
    assertThat(file(REPORT).readText()).isEqualTo(HEADER)
    val reused = run(*args)
    assertThat(reused.task(":dd-java-agent:instrumentation:aggregateMuzzleReports")?.outcome).describedAs(reused.output).isEqualTo(SUCCESS)
    assertThat(reused.task(":generateMuzzleReport")).isNull()
    assertThat(reused.output).contains("Reusing configuration cache")
  }

  private fun producerScript(directives: String) = """
    plugins {
      id("java")
      id("dd-trace-java.muzzle")
    }
    dependencies {
      implementation(project(":dd-java-agent:agent-tooling"))
    }
    $directives
  """.trimIndent()

  private fun writeReportingProject(directives: String) {
    writeProject(producerScript(directives))
    writeAggregationProject()
    writeFile("dd-java-agent/instrumentation/build.gradle.kts",
      """dependencies { implementation(project(":dd-java-agent:instrumentation:demo")) }""", append = true
    )
    writeJavaSource(
      "datadog.trace.agent.tooling.muzzle.MuzzleVersionScanPlugin",
      """
      package datadog.trace.agent.tooling.muzzle;

      public final class MuzzleVersionScanPlugin {
        public static java.util.Set<String> listInstrumentationNames(ClassLoader loader, String name) {
          return java.util.Collections.singleton(name == null ? "default" : name);
        }
      }
      """,
      projectPath = "dd-java-agent:agent-tooling"
    )
  }

  private fun addAggregationInput(projectPath: String, script: String) {
    addSubproject(projectPath, script)
    writeFile("dd-java-agent/instrumentation/build.gradle.kts",
      """dependencies { implementation(project(":$projectPath")) }""", append = true
    )
  }

  companion object {
    private const val HEADER = "instrumentation,jarGroupId,jarArtifactId,lowestVersion,highestVersion\n"
    private const val REPORT = "dd-java-agent/instrumentation/relocated/build/muzzle-deps-results/dd-java-agent_instrumentation.csv"
  }
}
