package datadog.gradle.plugin.muzzle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MuzzlePluginIntegrationTest {

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

    val reportFile = fixture.findSingleMuzzleJUnitReport()
    val report = fixture.parseXml(reportFile)
    val suite = report.documentElement
    assertEquals("testsuite", suite.tagName)
    assertEquals(":dd-java-agent:instrumentation:demo", suite.getAttribute("name"))
    assertEquals("1", suite.getAttribute("tests"))
    assertEquals("0", suite.getAttribute("failures"))

    val passCase = findTestCase(report, "muzzle-AssertPass-core-jdk")
    assertEquals(0, passCase.getElementsByTagName("failure").length)
    assertTrue(buildResult.output.contains(":dd-java-agent:instrumentation:demo:muzzle-end"))
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

    fixture.run(":dd-java-agent:instrumentation:demo:muzzle", "--stacktrace")

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

  private fun findTestCase(document: org.w3c.dom.Document, name: String): org.w3c.dom.Element =
    (0 until document.getElementsByTagName("testcase").length)
      .map { document.getElementsByTagName("testcase").item(it) as org.w3c.dom.Element }
      .first { it.getAttribute("name") == name }
}
