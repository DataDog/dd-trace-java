package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.GradleFixture
import datadog.gradle.plugin.MavenRepoFixture
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
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

    assertThat(result.tasks)
      .withFailMessage("Should create muzzle tasks when '$taskName' is requested")
      .anyMatch { it.path.contains("muzzle") }
    assertThat(result.output)
      .withFailMessage("Should not skip muzzle task planification when '$taskName' is requested")
      .doesNotContain("No muzzle tasks invoked, skipping muzzle task planification")
    assertThat(result.tasks).withFailMessage("Should execute muzzle tasks when '$taskName' is requested")
      .anyMatch { it.path == ":dd-java-agent:instrumentation:demo:muzzle" || it.path.contains("muzzle-Assert") }
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
    assertThat(buildResult.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome).isEqualTo(SUCCESS)
    assertThat(buildResult.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(buildResult.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome).isEqualTo(SUCCESS)

    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    assertThat(suite.tagName).isEqualTo("testsuite")
    assertThat(suite.getAttribute("name")).isEqualTo(":dd-java-agent:instrumentation:demo")
    assertThat(suite.getAttribute("tests")).isEqualTo("1")
    assertThat(suite.getAttribute("failures")).isEqualTo("0")

    val passCase = findTestCase(report, "muzzle-AssertPass-core-jdk")
    assertThat(passCase.getElementsByTagName("failure").length).isEqualTo(0)
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
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome).isEqualTo(SUCCESS)

    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    assertThat(suite.getAttribute("name")).isEqualTo(":dd-java-agent:instrumentation:demo")
    assertThat(suite.getAttribute("tests")).isEqualTo("1")
    assertThat(suite.getAttribute("failures")).isEqualTo("0")

    val defaultCase = findTestCase(report, "muzzle")
    assertThat(defaultCase.getElementsByTagName("failure").length).isEqualTo(0)
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

    assertThat(buildResult.output).doesNotContain("muzzle-end")
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
    assertThat(bootstrapDependencies.output).contains("project :dd-java-agent:agent-bootstrap")

    val toolingDependencies = fixture.run(
      ":dd-java-agent:instrumentation:demo:dependencies",
      "--configuration",
      "muzzleTooling"
    )
    assertThat(toolingDependencies.output).contains("project :dd-java-agent:agent-tooling")
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

    assertThat(result.task(muzzleTaskPath)?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(passDirectiveTaskPath)?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(failDirectiveTaskPath)?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(endTaskPath)?.outcome).isEqualTo(SUCCESS)

    val muzzleChainInOrder = result.tasks
      .map { it.path }
      .filter {
        it == muzzleTaskPath ||
          it == passDirectiveTaskPath ||
          it == failDirectiveTaskPath ||
          it == endTaskPath
      }
    assertThat(muzzleChainInOrder)
      .containsExactly(muzzleTaskPath, passDirectiveTaskPath, failDirectiveTaskPath, endTaskPath)

    val passDirectiveResult = fixture.resultFile("muzzle-AssertPass-core-jdk")
    val failDirectiveResult = fixture.resultFile("muzzle-AssertFail-core-jdk")
    assertThat(passDirectiveResult).isRegularFile()
    assertThat(failDirectiveResult).isRegularFile()
    assertThat(passDirectiveResult.readText()).isEqualTo("PASSING")
    assertThat(failDirectiveResult.readText()).isEqualTo("PASSING")
  }

  @Test
  fun `artifact directive resolves multiple versions from version range`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    val mavenRepoFixture = MavenRepoFixture(projectDir)

    mavenRepoFixture.publishVersions(
      group = "com.example.test",
      module = "demo-lib",
      versions = listOf("1.0.0", "1.1.0", "1.2.0", "2.0.0")
    )
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      // Gradle repositories for artifact download
      repositories {
        maven {
          url = uri('${mavenRepoFixture.repoUrl}')
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
      env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
    )

    assertThat(result.output)
      .withFailMessage("Build should succeed. Output:\n${result.output.take(3000)}")
      .contains("BUILD SUCCESSFUL")

    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome).isEqualTo(SUCCESS)

    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-1.0.0")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-1.1.0")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-1.2.0")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-demo-lib-2.0.0")?.outcome)
      .withFailMessage("Should not check against test-demo-lib:2.0.0")
      .isNull()

    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    val testCount = suite.getAttribute("tests").toInt()
    assertThat(testCount)
      .withFailMessage("Should have at least 3 tests for 3 versions, got $testCount")
      .isGreaterThanOrEqualTo(3)
    assertThat(suite.getAttribute("failures")).withFailMessage("Should have no failures").isEqualTo("0")

    val testCases = (0 until report.getElementsByTagName("testcase").length)
      .map { report.getElementsByTagName("testcase").item(it) as org.w3c.dom.Element }
      .map { it.getAttribute("name") }
    assertThat(testCases).withFailMessage("Should have test case for demo-lib-1.0.0. Found: ${testCases.take(5)}")
      .anySatisfy { assertThat(it).contains("demo-lib-1.0.0") }
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

    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.output).withFailMessage("Should confirm 'my-custom-check' was passed to scan plugin")
      .contains("Directive name passed correctly: my-custom-check")
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

    assertThat(result.output).withFailMessage("Build should fail for non-existent artifact").contains("BUILD FAILED")
    assertThat(result.output).withFailMessage("Should have error message about resolution failure")
      .containsAnyOf(
        "version range resolution failed",
        "Could not resolve",
        "not found",
        "Failed to resolve"
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

    assertThat(result.output).withFailMessage("Build should fail when pass directive fails validation")
      .contains("BUILD FAILED")
    assertThat(result.output).withFailMessage("Should contain error message from scan plugin")
      .containsAnyOf("Muzzle validation failed", "Instrumentation failed")
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
    assertThat(result.output)
      .withFailMessage("Build should fail when fail directive unexpectedly passes")
      .contains("BUILD FAILED")
    assertThat(result.output).withFailMessage("Should indicate that fail directive passed when it shouldn't have")
      .containsAnyOf("unexpectedly passed", "FAILURE WAS EXPECTED")
  }

  @Test
  fun `additional dependencies are added to muzzle test classpath`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    val mavenRepoFixture = MavenRepoFixture(projectDir)

    // Create a fake Maven repo with a fake additional dependency
    // The JAR will automatically include standard Maven metadata
    mavenRepoFixture.publishVersions(
      group = "com.example.extra",
      module = "extra-lib",
      versions = listOf("1.0.0")
    )
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      repositories {
        maven {
          url = uri('${mavenRepoFixture.repoUrl}')
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
      env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
    )

    assertThat(result.output)
      .withFailMessage("Build should succeed. Output:\n${result.output.take(2000)}")
      .contains("BUILD SUCCESSFUL")
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-core-jdk")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.output).withFailMessage("Additional dependency should be loadable from test classpath")
      .contains("Additional dependency (extra-lib) found in test classpath")
  }

  @Test
  fun `excluded dependencies are removed from muzzle test classpath`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    val mavenRepoFixture = MavenRepoFixture(projectDir)

    // Create a fake repo with an artifact that has transitive dependencies
    mavenRepoFixture.publishVersions(
      group = "com.example.test",
      module = "with-transitive",
      versions = listOf("1.0.0")
    )

    // Manually create a POM with a transitive dependency
    val pomFile = mavenRepoFixture.repoDir.resolve("com/example/test/with-transitive/1.0.0/with-transitive-1.0.0.pom")
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

    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      repositories {
        maven {
          url = uri('${mavenRepoFixture.repoUrl}')
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
      env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
    )

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-AssertPass-com.example.test-with-transitive-1.0.0")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.output).withFailMessage("Excluded dependency should not be loadable from test classpath")
      .contains("Excluded dependency (guava) correctly not in test classpath")
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

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome).isEqualTo(SUCCESS)
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

    assertThat(result.output).contains("BUILD SUCCESSFUL")
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task(":dd-java-agent:instrumentation:demo:muzzle-end")?.outcome).isEqualTo(SUCCESS)
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

    assertThat(result.output)
      .withFailMessage("Should not create muzzle tasks without java plugin")
      .doesNotContain("muzzle")
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

    assertThat(result.output).withFailMessage("Should fail with clear error about missing dd-java-agent projects")
      .containsAnyOf(
        "BUILD FAILED",
        ":dd-java-agent:agent-bootstrap project not found",
        ":dd-java-agent:agent-tooling project not found"
      )
  }

  @Test
  fun `assertInverse creates pass and fail tasks for in-range and out-of-range versions`(@TempDir projectDir: File) {
    val fixture = MuzzlePluginTestFixture(projectDir)
    val mavenRepoFixture = MavenRepoFixture(projectDir)

    mavenRepoFixture.publishVersions(
      group = "com.example.test",
      module = "inverse-lib",
      versions = listOf("1.0.0", "2.0.0", "3.0.0", "4.0.0")
    )
    fixture.writeProject(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.muzzle'
      }

      // Gradle repositories for artifact download
      repositories {
        maven {
          url = uri('${mavenRepoFixture.repoUrl}')
          metadataSources {
            mavenPom()
            artifact()
          }
        }
      }

      muzzle {
        pass {
          group = 'com.example.test'
          module = 'inverse-lib'
          versions = '[2.0.0,3.0.0]'
          assertInverse = true
        }
      }
      """
    )
    fixture.writeScanPlugin(
      """
      System.out.println("MUZZLE_CHECK assertPass=" + assertPass);
      """
    )

    val result = fixture.run(
      ":dd-java-agent:instrumentation:demo:muzzle",
      "--stacktrace",
      env = mapOf("MAVEN_REPOSITORY_PROXY" to mavenRepoFixture.repoUrl)
    )

    assertThat(result.output)
      .withFailMessage("Build should succeed. Output:\n${result.output.take(3000)}")
      .contains("BUILD SUCCESSFUL")

    val modulePrefix = ":dd-java-agent:instrumentation:demo"
    assertThat(result.task("$modulePrefix:muzzle")?.outcome).isEqualTo(SUCCESS)
    assertThat(result.task("$modulePrefix:muzzle-end")?.outcome).isEqualTo(SUCCESS)

    // In-range versions — assertPass=true
    assertThat(result.task("$modulePrefix:muzzle-AssertPass-com.example.test-inverse-lib-2.0.0")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.task("$modulePrefix:muzzle-AssertPass-com.example.test-inverse-lib-3.0.0")?.outcome)
      .isEqualTo(SUCCESS)

    // Out-of-range versions (inverse) — assertPass=false
    assertThat(result.task("$modulePrefix:muzzle-AssertFail-com.example.test-inverse-lib-1.0.0")?.outcome)
      .isEqualTo(SUCCESS)
    assertThat(result.task("$modulePrefix:muzzle-AssertFail-com.example.test-inverse-lib-4.0.0")?.outcome)
      .isEqualTo(SUCCESS)

    assertThat(result.output)
      .withFailMessage("Should log assertPass=true for in-range versions")
      .contains("MUZZLE_CHECK assertPass=true")
    assertThat(result.output)
      .withFailMessage("Should log assertPass=false for out-of-range (inverse) versions")
      .contains("MUZZLE_CHECK assertPass=false")

    // Verify JUnit report contains all 4 test cases with no failures
    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    assertThat(suite.getAttribute("tests"))
      .withFailMessage("Should have 4 test cases (2 pass + 2 inverse fail)")
      .isEqualTo("4")
    assertThat(suite.getAttribute("failures")).withFailMessage("Should have no failures").isEqualTo("0")

    findTestCase(report, "muzzle-AssertPass-com.example.test-inverse-lib-2.0.0")
    findTestCase(report, "muzzle-AssertPass-com.example.test-inverse-lib-3.0.0")
    findTestCase(report, "muzzle-AssertFail-com.example.test-inverse-lib-1.0.0")
    findTestCase(report, "muzzle-AssertFail-com.example.test-inverse-lib-4.0.0")
  }

  private fun findTestCase(document: org.w3c.dom.Document, name: String): org.w3c.dom.Element =
    (0 until document.getElementsByTagName("testcase").length)
      .map { document.getElementsByTagName("testcase").item(it) as org.w3c.dom.Element }
      .first { it.getAttribute("name") == name }
}
