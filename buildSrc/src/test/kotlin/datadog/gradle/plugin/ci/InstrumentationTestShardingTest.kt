package datadog.gradle.plugin.ci

import datadog.gradle.plugin.GradleFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.jupiter.api.Test
import kotlin.math.abs

class InstrumentationTestShardingTest : GradleFixture() {
  private val integration = ":dd-java-agent:instrumentation:example"

  @Test
  fun `test and check aggregates execute every class once across slots and preserve verification`() {
    prepareBuild()
    listOf("instrumentationTest", "instrumentationCheck").forEach { aggregate ->
      val executed = mutableListOf<String>()
      var verificationCount = 0
      for (slot in 1..3) {
        val result = run(aggregate, "-Pslot=$slot/3", "-PinstrumentationTestDurationsFile=durations.json", "--rerun-tasks")
        assertThat(result.task(":$aggregate")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
        executed.addAll(Regex("EXECUTED ([A-Za-z]+)").findAll(result.output).map { it.groupValues[1] }.toList())
        verificationCount += Regex("VERIFIED EXAMPLE").findAll(result.output).count()
      }
      val expected = listOf("Alpha", "NestedAlpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "New", "Version", "AlphaForked", "BravoForked") +
        if (aggregate == "instrumentationCheck") listOf("Latest") else emptyList()
      assertThat(executed).describedAs(aggregate).containsExactlyInAnyOrderElementsOf(expected)
      assertThat(verificationCount).isEqualTo(if (aggregate == "instrumentationCheck") 1 else 0)
    }
  }

  @Test
  fun `unsharded and malformed slots run complete suites and preserve existing filters`() {
    prepareBuild()
    listOf(emptyArray(), arrayOf("-Pslot=/1"), arrayOf("-Pslot=0/3"), arrayOf("-Pslot=4/3")).forEach { slotArgs ->
      val result = run("instrumentationTest", "-PinstrumentationTestDurationsFile=durations.json", "--rerun-tasks", *slotArgs)
      assertThat(result.task(":instrumentationTest")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
      assertThat(Regex("EXECUTED ([A-Za-z]+)").findAll(result.output).map { it.groupValues[1] }.toList())
        .containsExactlyInAnyOrder("Alpha", "NestedAlpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "New", "Version", "AlphaForked", "BravoForked")
    }
  }

  @Test
  fun `latest and non instrumentation tests retain their project slots`() {
    prepareBuild()
    val latestOwner = abs(integration.hashCode() % 3) + 1
    val smokeOwner = abs(":dd-smoke-tests:example".hashCode() % 3) + 1
    for (slot in 1..3) {
      val result = run("instrumentationLatestDepTest", "smokeTest", "-Pslot=$slot/3", "-PinstrumentationTestDurationsFile=durations.json", "--rerun-tasks")
      assertThat(result.task(":instrumentationLatestDepTest")?.outcome).describedAs(result.output).isIn(SUCCESS, UP_TO_DATE)
      assertThat(result.output.contains("EXECUTED Latest")).isEqualTo(slot == latestOwner)
      assertThat(result.output.contains("EXECUTED Smoke")).isEqualTo(slot == smokeOwner)
    }
  }

  @Test
  fun `changing slots invalidates a previously executed class subset`() {
    prepareBuild()
    val first = run("$integration:test", "-Pslot=1/3", "-PinstrumentationTestDurationsFile=durations.json")
    val second = run("$integration:test", "-Pslot=2/3", "-PinstrumentationTestDurationsFile=durations.json")
    val repeated = run("$integration:test", "-Pslot=2/3", "-PinstrumentationTestDurationsFile=durations.json")
    assertThat(first.task("$integration:test")?.outcome).describedAs(first.output).isEqualTo(SUCCESS)
    assertThat(second.task("$integration:test")?.outcome).describedAs(second.output).isEqualTo(SUCCESS)
    assertThat(repeated.task("$integration:test")?.outcome).describedAs(repeated.output).isEqualTo(UP_TO_DATE)
    val firstClasses = Regex("EXECUTED ([A-Za-z]+)").findAll(first.output).map { it.groupValues[1] }.toSet()
    val secondClasses = Regex("EXECUTED ([A-Za-z]+)").findAll(second.output).map { it.groupValues[1] }.toSet()
    assertThat(firstClasses).isNotEmpty()
    assertThat(secondClasses).isNotEmpty()
    assertThat(firstClasses.intersect(secondClasses)).isEmpty()
  }

  @Test
  fun `unselected version tasks do not schedule their compilation`() {
    prepareBuild()
    val durations = InstrumentationTestPlan.loadDurations(file("durations.json").readText())
    val plan = InstrumentationTestPlan.create(listOf(integration, ":dd-java-agent:instrumentation:empty"), durations, 3)
    val owner = plan.slotsForTask("$integration:versionTest").single()
    val slot = (1..3).first { it != owner }
    val result = run("instrumentationTest", "-Pslot=$slot/3", "-PinstrumentationTestDurationsFile=durations.json")
    assertThat(result.task(":instrumentationTest")?.outcome).describedAs(result.output).isEqualTo(SUCCESS)
    assertThat(result.task("$integration:compileVersionTestJava")).isNull()
  }

  private fun prepareBuild() {
    writeSettings("""rootProject.name = "sharding-test"""")
    writeFile(
      "build.gradle",
      """
      import static datadog.gradle.plugin.ci.CIJobsExtensionsKt.testAggregate
      plugins { id 'dd-trace-java.ci-jobs' }
      testAggregate(project, 'instrumentation', [':dd-java-agent:instrumentation'], [], false)
      testAggregate(project, 'smoke', [':dd-smoke-tests'], [], false)
    """
    )
    val moduleScript = """
      import static datadog.gradle.plugin.ci.InstrumentationTestShardingKt.getIncludeInInstrumentationAggregate
      plugins { id 'java' }
      repositories { mavenCentral() }
      dependencies {
        testImplementation 'org.junit.jupiter:junit-jupiter:5.14.4'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.14.4'
      }
      sourceSets { versionTest }
      configurations {
        versionTestImplementation.extendsFrom(testImplementation)
        versionTestRuntimeOnly.extendsFrom(testRuntimeOnly)
      }
      tasks.register('versionTest', Test) {
        testClassesDirs = sourceSets.versionTest.output.classesDirs
        classpath = sourceSets.versionTest.runtimeClasspath
      }
      tasks.register('latestDepTest', Test) {
        testClassesDirs = sourceSets.test.output.classesDirs
        classpath = sourceSets.test.runtimeClasspath
        include '**/LatestTest.class'
      }
      tasks.register('forkedTest', Test) {
        testClassesDirs = sourceSets.test.output.classesDirs
        classpath = sourceSets.test.runtimeClasspath
        include '**/*ForkedTest.class'
        forkEvery = 1
        doFirst { assert forkEvery == 1 }
      }
      tasks.named('test', Test) { exclude '**/LatestTest.class', '**/ExcludedTest.class', '**/*ForkedTest.class' }
      tasks.withType(Test).configureEach {
        useJUnitPlatform()
        testLogging.showStandardStreams = true
        failOnNoDiscoveredTests = false
      }
      tasks.register('allTests') {
        dependsOn tasks.withType(Test).matching { !it.name.contains('latest') && getIncludeInInstrumentationAggregate(it) }
      }
      tasks.register('allLatestDepTests') {
        dependsOn tasks.named('latestDepTest')
      }
      tasks.register('verifyExample') { doLast { println 'VERIFIED EXAMPLE' } }
      tasks.named('check') {
        dependsOn tasks.withType(Test).matching { getIncludeInInstrumentationAggregate(it) }
        dependsOn tasks.named('verifyExample')
      }
    """
    writeFile("settings.gradle.kts", """include("$integration", ":dd-java-agent:instrumentation:empty", ":dd-smoke-tests:example")""", append = true)
    writeFile("dd-java-agent/instrumentation/example/build.gradle", moduleScript)
    writeFile("dd-java-agent/instrumentation/empty/build.gradle", """tasks.register('allTests')""")
    writeFile("dd-smoke-tests/example/build.gradle", moduleScript)
    val classes = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "New", "Latest", "Excluded", "AlphaForked", "BravoForked")
    classes.forEach { name ->
      writeJavaSource("example.${name}Test", javaTest(name), sourceSet = "test", projectPath = integration)
    }
    writeJavaSource("example.VersionTest", javaTest("Version"), sourceSet = "versionTest", projectPath = integration)
    writeJavaSource("example.SmokeTest", javaTest("Smoke"), sourceSet = "test", projectPath = ":dd-smoke-tests:example")
    writeFile(
      "durations.json",
      """
      {"tasks": {
        "$integration:test": {"example.AlphaTest":80, "example.BravoTest":80, "example.CharlieTest":80, "example.DeltaTest":80, "example.EchoTest":80, "example.FoxtrotTest":80},
        "$integration:versionTest": {"example.VersionTest":30},
        "$integration:forkedTest": {"example.AlphaForkedTest":150, "example.BravoForkedTest":150}
      }}
    """
    )
  }

  private fun javaTest(name: String): String = """
    package example;
    import org.junit.jupiter.api.Test;
    public class ${name}Test {
      @Test void runs() { System.out.println("EXECUTED $name"); }
      ${if (name == "Alpha") {
    """
      @org.junit.jupiter.api.Nested class Inner {
        @Test void runs() { System.out.println("EXECUTED NestedAlpha"); }
      }
      """
  } else {
    ""
  }}
    }
  """
}
