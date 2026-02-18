package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.GradleFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import kotlin.io.path.readText

class MuzzlePluginFunctionalTest {
  @ParameterizedTest
  @ValueSource(strings = ["muzzle", ":dd-java-agent:instrumentation:demo:muzzle", "runMuzzle"])
  fun `detects muzzle invocation with various task names`(
    taskName: String,
    @TempDir projectDir: File
  ) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass {
          coreJdk()
        }
      }
      """
    )

    // Add runMuzzle aggregator task at root level (like in dd-trace-java.ci-jobs.gradle.kts)
    fixture.writeRootProject(
      """
      tasks.register('runMuzzle') {
        dependsOn(':dd-java-agent:instrumentation:demo:muzzle')
      }
      """
    )

    fixture.writeNoopScanPlugin()

    val result = fixture.run(taskName, "--stacktrace")

    assertTrue(
      result.tasks.any { it.path.contains("muzzle") },
      "Should create muzzle tasks when '$taskName' is requested"
    )
    assertFalse(
      result.output.contains("No muzzle tasks invoked, skipping muzzle task planification"),
      "Should not skip muzzle task planification when '$taskName' is requested"
    )
    assertTrue(
      result.task(":dd-java-agent:instrumentation:demo:muzzle") != null ||
          result.tasks.any { it.path.contains("muzzle-Assert") },
      "Should execute muzzle tasks when '$taskName' is requested"
    )
  }

  @Test
  fun `muzzle with pass directive writes junit report`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      
      muzzle {
        pass {
          name = 'expected-pass'
          coreJdk()
        }
      }
      """
    )
    fixture.writeScanPlugin(
      """
      if (!assertPass) {
        throw new IllegalStateException("unexpected fail assertion for " + muzzleDirective);
      }
      """
    )

    val buildResult = fixture.run(":dd-java-agent:instrumentation:demo:muzzle", "--stacktrace")
    assertEquals(SUCCESS, buildResult.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
    assertEquals(SUCCESS, buildResult.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
    assertEquals(SUCCESS, buildResult.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome)

    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    assertEquals("testsuite", suite.tagName)
    assertEquals(":dd-java-agent:instrumentation:demo", suite.getAttribute("name"))
    assertEquals("1", suite.getAttribute("tests"))
    assertEquals("0", suite.getAttribute("failures"))

    val passCase = findTestCase(report, "muzzle-AssertPass-core-jdk")
    assertEquals(0, passCase.getElementsByTagName("failure").length)
  }

  @Test
  fun `muzzle without directives writes default junit report`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      """
    )
    fixture.writeScanPlugin(
      """
      if (!assertPass) {
        throw new IllegalStateException("unexpected fail assertion for " + muzzleDirective);
      }
      """
    )

    val result = fixture.run(":dd-java-agent:instrumentation:demo:muzzle", "--stacktrace")
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)

    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    assertEquals(":dd-java-agent:instrumentation:demo", suite.getAttribute("name"))
    assertEquals("1", suite.getAttribute("tests"))
    assertEquals("0", suite.getAttribute("failures"))

    val defaultCase = findTestCase(report, "muzzle")
    assertEquals(0, defaultCase.getElementsByTagName("failure").length)
  }

  @Test
  fun `non muzzle invocation does not register muzzle end task`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      
      muzzle {
        pass {
          coreJdk()
        }
      }
      """
    )

    val buildResult = fixture.run(":dd-java-agent:instrumentation:demo:tasks", "--all")

    assertFalse(buildResult.output.contains("muzzle-end"))
  }

  @Test
  fun `muzzle plugin wires bootstrap and tooling project classpaths`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      """
    )

    val bootstrapDependencies = fixture.run(
      ":dd-java-agent:instrumentation:demo:dependencies",
      "--configuration",
      "muzzleBootstrap"
    )
    assertTrue(bootstrapDependencies.output.contains("project :dd-java-agent:agent-bootstrap"))

    val toolingDependencies = fixture.run(
      ":dd-java-agent:instrumentation:demo:dependencies",
      "--configuration",
      "muzzleTooling"
    )
    assertTrue(toolingDependencies.output.contains("project :dd-java-agent:agent-tooling"))
  }

  @Test
  fun `muzzle executes exactly planned core-jdk tasks and writes task results`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }
      
      muzzle {
        pass { coreJdk() }
        fail { coreJdk() }
      }
      """
    )
    fixture.writeScanPlugin(
      """
      // pass
      """
    )

    val result = fixture.run(":dd-java-agent:instrumentation:demo:muzzle", "--stacktrace")
    val muzzleTaskPath = ":dd-java-agent:instrumentation:demo:muzzle"
    val passDirectiveTaskPath = ":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk"
    val failDirectiveTaskPath = ":dd-java-agent:instrumentation:demo:muzzle-AssertFail-core-jdk"
    val endTaskPath = ":dd-java-agent:instrumentation:demo:muzzle-end"

    assertEquals(SUCCESS, result.task(muzzleTaskPath)?.outcome)
    assertEquals(SUCCESS, result.task(passDirectiveTaskPath)?.outcome)
    assertEquals(SUCCESS, result.task(failDirectiveTaskPath)?.outcome)
    assertEquals(SUCCESS, result.task(endTaskPath)?.outcome)

    val muzzleChainInOrder = result.tasks
      .map { it.path }
      .filter {
        it == muzzleTaskPath ||
          it == passDirectiveTaskPath ||
          it == failDirectiveTaskPath ||
          it == endTaskPath
      }
    assertEquals(
      listOf(muzzleTaskPath, passDirectiveTaskPath, failDirectiveTaskPath, endTaskPath),
      muzzleChainInOrder
    )

    val passDirectiveResult = fixture.resultFile("muzzle-AssertPass-core-jdk")
    val failDirectiveResult = fixture.resultFile("muzzle-AssertFail-core-jdk")
    assertTrue(Files.isRegularFile(passDirectiveResult))
    assertTrue(Files.isRegularFile(failDirectiveResult))
    assertEquals("PASSING", passDirectiveResult.readText())
    assertEquals("PASSING", failDirectiveResult.readText())
  }

  @Test
  fun `artifact directive resolves multiple versions from version range`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    val repoDir = fixture.createFakeMavenRepo(
      group = "com.example.test",
      module = "demo-lib",
      versions = listOf("1.0.0", "1.1.0", "1.2.0", "2.0.0")
    )

    val repoUrl = repoDir.toURI().toString()
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      // Gradle repositories for artifact download
      repositories {
        maven {
          url = uri('$repoUrl')
          metadataSources {
            mavenPom()
            artifact()
            // Disable checksum validation for fake repo
          }
        }
      }

      muzzle {
        pass {
          group = 'com.example.test'
          module = 'demo-lib'
          versions = '[1.0.0,2.0.0)'  // Should resolve 1.0.0, 1.1.0, 1.2.0 but NOT 2.0.0
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    // Leveraging MAVEN_REPOSITORY_PROXY to point to our fake repo over maven central
    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to repoUrl)
    )

    assertTrue(
      result.output.contains("BUILD SUCCESSFUL"),
      "Build should succeed. Output:\n${result.output.take(3000)}"
    )

    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome)

    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-1.0.0")?.outcome)
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-1.1.0")?.outcome)
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-1.2.0")?.outcome)
    assertNull(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-2.0.0")?.outcome, "Should not check against test-demo-lib:2.0.0")

    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    val testCount = suite.getAttribute("tests").toInt()
    assertTrue(testCount >= 3, "Should have at least 3 tests for 3 versions, got $testCount")
    assertEquals("0", suite.getAttribute("failures"), "Should have no failures")

    val testCases = (0 until report.getElementsByTagName("testcase").length)
      .map { report.getElementsByTagName("testcase").item(it) as org.w3c.dom.Element }
      .map { it.getAttribute("name") }
    assertTrue(
      testCases.any { it.contains("demo-lib-1.0.0") },
      "Should have test case for demo-lib-1.0.0. Found: ${testCases.take(5)}"
    )
  }

  @Test
  fun `named directive is passed to scan plugin`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass {
          name = 'my-custom-check'
          coreJdk()
        }
      }
      """
    )

    // The real MuzzleVersionScanPlugin uses the directive name to filter InstrumenterModules
    fixture.writeScanPlugin(
      """
      if (!"my-custom-check".equals(muzzleDirective)) {
        throw new IllegalStateException(
          "Expected muzzleDirective to be 'my-custom-check', but got: '" + muzzleDirective + "'"
        );
      }

      System.out.println("Directive name passed correctly: " + muzzleDirective);
      """
    )

    val result = fixture.run(":dd-java-agent:instrumentation:demo:muzzle", "--stacktrace")

    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
    assertTrue(
      result.output.contains("Directive name passed correctly: my-custom-check"),
      "Should confirm 'my-custom-check' was passed to scan plugin"
    )
  }

  @Test
  fun `non-existent artifact fails with clear error message`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass {
          group = 'com.example.nonexistent'
          module = 'does-not-exist'
          versions = '[1.0.0,2.0.0)'
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to "https://repo1.maven.org/maven2/")
    )

    assertTrue(result.output.contains("BUILD FAILED"), "Build should fail for non-existent artifact")
    assertTrue(
      result.output.contains("version range resolution failed") ||
      result.output.contains("Could not resolve") ||
      result.output.contains("not found") ||
      result.output.contains("Failed to resolve"),
      "Should have error message about resolution failure"
    )
  }

  @Test
  fun `pass directive that fails validation causes build failure`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass {
          coreJdk()
        }
      }
      """
    )

    // Real implementation throws RuntimeException when !passed && assertPass (line 70 of MuzzleVersionScanPlugin)
    fixture.writeScanPlugin(
      """
      if (assertPass) {
        System.err.println("FAILED MUZZLE VALIDATION: mismatches:");
        System.err.println("-- missing class Foo");
        throw new RuntimeException("Instrumentation failed Muzzle validation");
      }
      """
    )

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace"
    )

    assertTrue(result.output.contains("BUILD FAILED"), "Build should fail when pass directive fails validation")
    assertTrue(
      result.output.contains("Muzzle validation failed") ||
      result.output.contains("Instrumentation failed"),
      "Should contain error message from scan plugin"
    )
  }

  @Test
  @Disabled("Current implementation doesn't fail build when fail directive unexpectedly passes - MuzzleTask catches exceptions")
  fun `fail directive that passes validation causes build failure`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        fail {
          coreJdk()
        }
      }
      """
    )

    // Scan plugin simulates successful validation when it should fail
    // Real MuzzleVersionScanPlugin throws RuntimeException when passed && !assertPass
    fixture.writeScanPlugin(
      """
      if (!assertPass) {
        System.err.println("MUZZLE PASSED BUT FAILURE WAS EXPECTED");
        throw new RuntimeException("Instrumentation unexpectedly passed Muzzle validation");
      }
      """
    )

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace"
    )

    // Expected behavior: build should fail when fail directive unexpectedly passes
    assertTrue(result.output.contains("BUILD FAILED"), "Build should fail when fail directive unexpectedly passes")
    assertTrue(
      result.output.contains("unexpectedly passed") ||
      result.output.contains("FAILURE WAS EXPECTED"),
      "Should indicate that fail directive passed when it shouldn't have"
    )
  }

  @Test
  fun `additional dependencies are added to muzzle test classpath`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    // Create a fake Maven repo with a fake additional dependency
    // The JAR will automatically include standard Maven metadata
    val repoDir = fixture.createFakeMavenRepo(
      group = "com.example.extra",
      module = "extra-lib",
      versions = listOf("1.0.0")
    )

    val repoUrl = repoDir.toURI().toString()
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      repositories {
        maven {
          url = uri('$repoUrl')
          metadataSources {
            mavenPom()
            artifact()
          }
        }
      }

      muzzle {
        pass {
          coreJdk()
          extraDependency('com.example.extra:extra-lib:1.0.0')
        }
      }
      """
    )

    // Scan plugin verifies that the additional dependency JAR is in the classpath
    fixture.writeScanPlugin(
      """
      java.io.InputStream resource = testApplicationClassLoader.getResourceAsStream("META-INF/maven/com.example.extra/extra-lib/pom.properties");
      if (resource != null) {
        try {
          resource.close();
        } catch (java.io.IOException e) {
          // Ignore
        }
        System.out.println("Additional dependency (extra-lib) found in test classpath");
      } else {
        throw new RuntimeException("Additional dependency (extra-lib) not found in test classpath");
      }
      """
    )

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to repoUrl)
    )

    assertTrue(
      result.output.contains("BUILD SUCCESSFUL"),
      "Build should succeed. Output:\n${result.output.take(2000)}"
    )
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
    assertTrue(
      result.output.contains("Additional dependency (extra-lib) found in test classpath"),
      "Additional dependency should be loadable from test classpath"
    )
  }

  @Test
  fun `excluded dependencies are removed from muzzle test classpath`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    // Create a fake repo with an artifact that has transitive dependencies
    val repoDir = fixture.createFakeMavenRepo(
      group = "com.example.test",
      module = "with-transitive",
      versions = listOf("1.0.0")
    )

    // Manually create a POM with a transitive dependency
    val pomFile = repoDir.resolve("com/example/test/with-transitive/1.0.0/with-transitive-1.0.0.pom")
    pomFile.writeText(
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example.test</groupId>
        <artifactId>with-transitive</artifactId>
        <version>1.0.0</version>
        <dependencies>
          <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
            <version>31.0-jre</version>
          </dependency>
        </dependencies>
      </project>
      """.trimIndent()
    )

    val repoUrl = repoDir.toURI().toString()
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      repositories {
        maven {
          url = uri('$repoUrl')
          metadataSources {
            mavenPom()
            artifact()
          }
        }
        mavenCentral()
      }

      muzzle {
        pass {
          group = 'com.example.test'
          module = 'with-transitive'
          versions = '1.0.0'
          excludeDependency('com.google.guava:guava')
        }
      }
      """
    )

    // Scan plugin verifies that guava is NOT in the classpath (it was excluded)
    fixture.writeScanPlugin(
      """
      try {
        testApplicationClassLoader.loadClass("com.google.common.collect.ImmutableList");
        throw new RuntimeException("Unexpected excluded dependency (guava) SHOULD NOT be in test classpath but was found");
      } catch (ClassNotFoundException e) {
        System.out.println("Excluded dependency (guava) correctly not in test classpath");
      }
      """
    )

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to repoUrl)
    )

    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-with-transitive-1.0.0")?.outcome)
    assertTrue(
      result.output.contains("Excluded dependency (guava) correctly not in test classpath"),
      "Excluded dependency should not be loadable from test classpath"
    )
  }

  @Test
  fun `java plugin applied after muzzle plugin`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'dd-trace-java.muzzle'
      }
      
      // applied after muzzle plugin
      apply plugin: 'java'

      muzzle {
        pass {
          coreJdk()
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace"
    )

    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome)
  }

  @Test
  fun `java plugin applied before muzzle plugin`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle' apply false  // Declared but not applied
      }

      // Apply muzzle plugin after java using imperative syntax
      apply plugin: 'dd-trace-java.muzzle'

      muzzle {
        pass {
          coreJdk()
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace"
    )

    assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome)
    assertEquals(SUCCESS, result.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome)
  }

  @Test
  fun `plugin behavior without java plugin should no-op`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)

    fixture.writeProject(
      """
      plugins {
        id 'dd-trace-java.muzzle'
        // NO java plugin applied
      }

      muzzle {
        pass {
          coreJdk()
        }
      }
      """
    )
    fixture.writeNoopScanPlugin()

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:tasks",
      "--all"
    )

    assertFalse(
      result.output.contains("muzzle"),
      "Should not create muzzle tasks without java plugin"
    )
  }

  @Test
  fun `missing dd-java-agent projects error handling`(@TempDir projectDir: File) {
    // Create a minimal settings.gradle without the dd-java-agent structure
    File(projectDir, "settings.gradle").also { it.parentFile?.mkdirs() }.writeText(
      """
      rootProject.name = 'muzzle-test'
      include ':instrumentation:demo'
      """.trimIndent()
    )

    File(projectDir, "instrumentation/demo/build.gradle").also { it.parentFile?.mkdirs() }.writeText(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      muzzle {
        pass {
          coreJdk()
        }
      }
      """.trimIndent()
    )

    // No need to create MuzzleVersionScanPlugin - the error happens during configuration
    // phase before any task execution, so the scan plugin is never invoked

    val result = GradleFixture(projectDir).run(
      ":instrumentation:demo:tasks",
      "--stacktrace"
    )

    assertTrue(
      result.output.contains("BUILD FAILED") ||
      result.output.contains(":dd-java-agent:agent-bootstrap project not found") ||
      result.output.contains(":dd-java-agent:agent-tooling project not found"),
      "Should fail with clear error about missing dd-java-agent projects"
    )
  }

  private fun findTestCase(document: org.w3c.dom.Document, name: String): org.w3c.dom.Element =
    (0 until document.getElementsByTagName("testcase").length)
      .map { document.getElementsByTagName("testcase").item(it) as org.w3c.dom.Element }
      .first { it.getAttribute("name") == name }
}
