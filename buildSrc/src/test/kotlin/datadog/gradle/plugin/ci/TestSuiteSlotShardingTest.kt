package datadog.gradle.plugin.ci

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome.SKIPPED
import org.junit.jupiter.api.Test
import org.tabletest.junit.TableTest
import kotlin.math.abs

class TestSuiteSlotShardingTest : GradleFixture() {
  @TableTest(
    """
    Scenario               | Git filtering | Unaffected project's test runs
    Full test run          | false         | true
    Only one suite changed | true          | false
    """,
  )
  fun `suite shards execute exactly the original aggregate test tasks`(gitFiltering: Boolean, runsUnaffectedProject: Boolean) {
    setupAggregate("inst", "unaffected")
    writeFile(
      "inst/build.gradle.kts",
      """
      import datadog.gradle.plugin.ci.useSuiteShard

      plugins { java }

      val changed by sourceSets.creating
      val unchanged by sourceSets.creating
      val legacy = recordingTest("test_legacy", changed)
      val forked = recordingTest("test_legacyForkedTest", changed) {
        useSuiteShard(legacy.name)
      }
      val sibling = recordingTest("siblingTest", unchanged)

      val probe = recordingTest("agentProbe", unchanged)
      val prepare = tasks.register("prepare") { dependsOn(probe) }
      val reactor = recordingTest("latestReactorTest", unchanged)
      val latest = recordingTest("latestDepTest", changed) {
        dependsOn(prepare)
        finalizedBy(reactor)
      }

      tasks.register("allTests") { dependsOn(legacy, forked, sibling) }
      tasks.register("allLatestDepTests") { dependsOn(latest, reactor) }
      """ + recordingTestHelper,
    )
    writeJavaSource("Changed", "class Changed {}", "changed", "inst")
    writeJavaSource("Unchanged", "class Unchanged {}", "unchanged", "inst")
    setupDefaultTestProject("unaffected")

    val gitArgs = if (gitFiltering) {
      initGitRepo()
      writeJavaSource("Changed", "class Changed { void updated() {} }", "changed", "inst")
      commitAll("Modify only the changed suite")
      arrayOf("-PgitBaseRef=HEAD~1")
    } else {
      emptyArray()
    }
    val targets = arrayOf("ciTest", "ciLatestDepTest", "--rerun-tasks", "--no-build-cache")
    val baseline = executedTests(run(*targets, *gitArgs))
    val expected = listOf(
      ":inst:test_legacy",
      ":inst:test_legacyForkedTest",
      ":inst:siblingTest",
      ":inst:latestDepTest",
      ":inst:latestReactorTest",
      ":inst:agentProbe",
    ) + if (runsUnaffectedProject) listOf(":unaffected:test") else emptyList()
    assertThat(baseline).containsExactlyInAnyOrderElementsOf(expected)

    val totalSlots = 4
    val unaffectedSlot = slotFor(":unaffected", totalSlots)
    val bySlot = (1..totalSlots).associateWith {
      val result = run(*targets, *gitArgs, "-Pslot=$it/$totalSlots")
      if (it != unaffectedSlot) {
        assertThat(result.output).doesNotContain("CONFIGURED :unaffected:test")
      }
      executedTests(result)
    }
    // List comparison checks both the union and that each action ran exactly once.
    assertThat(bySlot.values.flatten()).containsExactlyInAnyOrderElementsOf(baseline)

    val legacySlot = slotFor(":inst:test_legacy", totalSlots)
    val siblingSlot = slotFor(":inst:siblingTest", totalSlots)
    assertThat(legacySlot).isNotEqualTo(siblingSlot)
    assertThat(bySlot.getValue(legacySlot)).contains(":inst:test_legacy", ":inst:test_legacyForkedTest")
    assertThat(bySlot.getValue(siblingSlot)).contains(":inst:siblingTest")
    if (runsUnaffectedProject) {
      assertThat(bySlot.getValue(unaffectedSlot)).contains(":unaffected:test")
    }

    val relationshipSlot = bySlot.values.single { ":inst:latestDepTest" in it }
    assertThat(relationshipSlot).containsSubsequence(":inst:agentProbe", ":inst:latestDepTest", ":inst:latestReactorTest")
  }

  @Test
  fun `sharded checks and direct tests execute in the project slot`() {
    setupAggregate("modA", "modB")
    setupDefaultTestProject("modA")
    setupDefaultTestProject("modB")

    val totalSlots = 8
    val projectSlot = slotFor(":modA", totalSlots)
    val suiteSlot = slotFor(":modA:test", totalSlots)
    assertThat(projectSlot).isNotEqualTo(suiteSlot)

    val checkResult = run("ciCheck", "-Pslot=$projectSlot/$totalSlots")
    assertThat(executedTests(checkResult)).containsExactly(":modA:test")
    assertThat(checkResult.task(":modB:check")).isNull()

    assertThat(executedTests(run(":modA:test", "-Pslot=$projectSlot/$totalSlots", "--rerun-tasks")))
      .containsExactly(":modA:test")
    val wrongSlotResult = run(":modA:test", "-Pslot=$suiteSlot/$totalSlots", "--rerun-tasks")
    assertThat(executedTests(wrongSlotResult)).isEmpty()
    assertThat(wrongSlotResult.task(":modA:test")?.outcome).isEqualTo(SKIPPED)
  }

  @Test
  fun `jacoco project retains project placement for its tests and coverage tasks`() {
    setupAggregate("core")
    writeFile(
      "core/build.gradle.kts",
      """
      plugins {
        java
        jacoco
      }

      val extraTest = recordingTest("extraTest", sourceSets.test.get())
      tasks.register("allTests") { dependsOn(tasks.test, extraTest) }
      """ + recordingTestHelper,
    )
    val totalSlots = 8
    val projectSlot = slotFor(":core", totalSlots)
    val suiteSlot = slotFor(":core:extraTest", totalSlots)
    assertThat(suiteSlot).isNotEqualTo(projectSlot)

    val selected = run("ciTest", "-PcheckCoverage", "-Pslot=$projectSlot/$totalSlots", "--dry-run").output
    assertThat(selected).contains(
      ":core:test SKIPPED",
      ":core:extraTest SKIPPED",
      ":core:jacocoTestReport SKIPPED",
      ":core:jacocoTestCoverageVerification SKIPPED",
    )
    val other = run("ciTest", "-PcheckCoverage", "-Pslot=$suiteSlot/$totalSlots", "--dry-run").output
    assertThat(other).contains(":ciTest SKIPPED").doesNotContain(":core:")
  }

  @Test
  fun `default suite keeps the project slot and suites sharing setup share a slot`() {
    setupAggregate("multi", "plain")
    writeFile(
      "multi/build.gradle.kts",
      """
      import datadog.gradle.plugin.ci.useSuiteShard

      plugins { java }

      tasks.test {
        actions.clear()
        doLast { println("EXECUTED " + path) }
      }
      val forked = recordingTest("forkedTest", sourceSets.test.get()) { useSuiteShard("test") }
      val integration = recordingTest("integrationTest", sourceSets.test.get())
      val buildApp = tasks.register("buildApp") { doLast { println("EXECUTED " + path) } }
      val app = recordingTest("appTest", sourceSets.test.get()) { dependsOn(buildApp) }
      val otherApp = recordingTest("otherAppTest", sourceSets.test.get()) { dependsOn(buildApp) }
      val latest = recordingTest("latestDepTest", sourceSets.test.get())

      tasks.register("allTests") { dependsOn(tasks.test, forked, integration, app, otherApp) }
      tasks.register("allLatestDepTests") { dependsOn(latest) }
      """ + recordingTestHelper,
    )
    writeJavaSource("Sample", "class Sample {}", "test", "multi")
    writeFile(
      "plain/build.gradle.kts",
      """
      plugins { java }

      tasks.test {
        println("CONFIGURED " + path)
        actions.clear()
        doLast { println("EXECUTED " + path) }
      }
      val latest = tasks.register<Test>("latestDepTest") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        actions.clear()
      }
      tasks.register("allTests") { dependsOn(tasks.test) }
      tasks.register("allLatestDepTests") { dependsOn(latest) }
      """,
    )
    writeJavaSource("Sample", "class Sample {}", "test", "plain")

    val totalSlots = 8
    val projectSlot = slotFor(":multi", totalSlots)
    val integrationSlot = slotFor(":multi:integrationTest", totalSlots)
    val appSlot = slotFor(":multi:appTest", totalSlots)
    assertThat(setOf(projectSlot, integrationSlot, appSlot)).hasSize(3)
    val plainSlot = slotFor(":plain", totalSlots)

    val bySlot = (1..totalSlots).associateWith {
      val result = run("ciTest", "--rerun-tasks", "-Pslot=$it/$totalSlots")
      if (it != plainSlot) {
        // A project whose aggregate only runs its default suite is not set up on other runners.
        assertThat(result.output).doesNotContain("CONFIGURED :plain:test")
      }
      executedTests(result)
    }
    assertThat(bySlot.values.flatten()).containsExactlyInAnyOrder(
      ":multi:test",
      ":multi:forkedTest",
      ":multi:integrationTest",
      ":multi:buildApp",
      ":multi:appTest",
      ":multi:otherAppTest",
      ":plain:test",
    )
    assertThat(bySlot.getValue(projectSlot)).contains(":multi:test", ":multi:forkedTest")
    assertThat(bySlot.getValue(integrationSlot)).contains(":multi:integrationTest")
    assertThat(bySlot.getValue(appSlot)).contains(":multi:buildApp", ":multi:appTest", ":multi:otherAppTest")
    assertThat(bySlot.getValue(plainSlot)).contains(":plain:test")
  }

  @Test
  fun `a root aggregate that is not scheduled selects no suites`() {
    setupAggregate("multi")
    writeFile(
      "multi/build.gradle.kts",
      """
      plugins { java }

      val integration = recordingTest("integrationTest", sourceSets.test.get())
      tasks.register("allTests") { dependsOn(tasks.test, integration) }
      """ + recordingTestHelper,
    )
    val totalSlots = 8
    val integrationSlot = slotFor(":multi:integrationTest", totalSlots)
    assertThat(integrationSlot).isNotEqualTo(slotFor(":multi", totalSlots))

    // Excluding the aggregate configures it without scheduling it.
    val result = run(":multi:integrationTest", "-x", ":ciTest", "--rerun-tasks", "-Pslot=$integrationSlot/$totalSlots")
    assertThat(executedTests(result)).isEmpty()
    assertThat(result.task(":multi:integrationTest")?.outcome).isEqualTo(SKIPPED)
  }

  @Test
  fun `inferred links between suite groups fail the build`() {
    setupAggregate("linked")
    writeFile(
      "linked/build.gradle.kts",
      """
      plugins { java }

      val b = recordingTest("bTest", sourceSets.test.get())
      // Consuming the outputs of another test is an inferred dependency, invisible to grouping.
      val a = recordingTest("aTest", sourceSets.test.get()) { inputs.files(b) }
      tasks.register("allTests") { dependsOn(a, b) }
      """ + recordingTestHelper,
    )
    val totalSlots = 4
    val aSlot = slotFor(":linked:aTest", totalSlots)

    val result = run("ciTest", "--dry-run", "-Pslot=$aSlot/$totalSlots", expectFailure = true)
    assertThat(result.output)
      .contains("These Test tasks depend on tests that -Pslot places on other runners")
      .contains(":linked:aTest -> :linked:bTest")
  }

  private fun setupAggregate(vararg projects: String) {
    val projectPaths = projects.joinToString { "\":$it\"" }
    writeSettings(
      """
      rootProject.name = "ci-sharding"
      include($projectPaths)
      """,
    )
    writeRootProject(
      """
      import datadog.gradle.plugin.ci.testAggregate

      plugins { id("dd-trace-java.ci-jobs") }

      testAggregate("ci", listOf($projectPaths))
      """,
    )
  }

  private fun setupDefaultTestProject(project: String) {
    writeFile(
      "$project/build.gradle.kts",
      """
      plugins { java }

      tasks.test {
        println("CONFIGURED " + path)
        actions.clear()
        doLast { println("EXECUTED " + path) }
      }
      tasks.register("allTests") { dependsOn(tasks.test) }
      """,
    )
    writeJavaSource("Sample", "class Sample {}", "test", project)
  }

  private fun executedTests(result: BuildResult): List<String> {
    assertThat(result.output).doesNotContain("BUILD FAILED")
    return result.output.lineSequence()
      .filter { it.startsWith("EXECUTED ") }
      .map { it.removePrefix("EXECUTED ") }
      .toList()
  }

  private fun slotFor(key: String, totalSlots: Int): Int = abs(key.hashCode() % totalSlots) + 1

  // Use Test tasks with a deterministic action so the fixture exercises task execution and
  // onlyIf without downloading an external test engine.
  private val recordingTestHelper = """

      fun recordingTest(name: String, sources: SourceSet, configure: Test.() -> Unit = {}) =
        tasks.register<Test>(name) {
          testClassesDirs = sources.output.classesDirs
          classpath = sources.runtimeClasspath
          actions.clear()
          doLast { println("EXECUTED " + path) }
          configure()
        }
  """
}
