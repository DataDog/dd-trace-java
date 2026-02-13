package datadog.gradle.plugin.muzzle.tasks

import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MuzzleEndTaskTest {

  @TempDir
  lateinit var tempDir: Path

  private lateinit var junitDoc: Document
  private lateinit var legacyDoc: Document

  @BeforeEach
  fun setup() {
    val rootProject = ProjectBuilder.builder()
      .withProjectDir(tempDir.toFile())
      .withName("root")
      .build()

    val childProjectDir = tempDir.resolve("lettuce-5.0").createDirectories().toFile()
    val project = ProjectBuilder.builder()
      .withParent(rootProject)
      .withName("lettuce-5.0")
      .withProjectDir(childProjectDir)
      .build()

    val passReportPath = project.layout.buildDirectory.file("reports/muzzle-pass.txt").get().asFile.toPath().apply {
      parent.createDirectories()
      writeText("PASSING")
    }

    val failReportPath = project.layout.buildDirectory.file("reports/muzzle-fail.txt").get().asFile.toPath().apply {
      parent.createDirectories()
      writeText("java.lang.IllegalStateException: something is broken")
    }

    val task = project.tasks.register<MuzzleEndTask>("muzzle-end").get().apply {
      startTimeMs.set(System.currentTimeMillis() - 1_000)
      muzzleResultFiles.from(passReportPath.toFile(), failReportPath.toFile())
    }

    // Pre run the task
    task.generatesResultFile()

    val junitReportXml = project.layout.buildDirectory
      .file("test-results/muzzle/TEST-muzzle-lettuce-5.0.xml")
      .get().asFile.toPath().readText()
    junitDoc = parseXml(junitReportXml)

    val legacyReportXml = rootProject.layout.buildDirectory
      .file("muzzle-test-results/lettuce-5.0_muzzle/results.xml")
      .get().asFile.toPath().readText()
    legacyDoc = parseXml(legacyReportXml)
  }

  @Test
  fun `junit report contains expected testsuite counters`() {
    val suite = junitDoc.documentElement
    assertEquals("testsuite", suite.tagName)
    assertEquals("2", suite.getAttribute("tests"))
    assertEquals("1", suite.getAttribute("failures"))
    assertEquals("0", suite.getAttribute("errors"))
    assertEquals("0", suite.getAttribute("skipped"))
  }

  @Test
  fun `passed testcase has no failure node`() {
    val passedTestCase = findTestCaseByName(junitDoc, "muzzle-pass")
    assertNotNull(passedTestCase)
    assertNull(passedTestCase.getElementsByTagName("failure").item(0))
  }

  @Test
  fun `failed testcase contains failure node and message`() {
    val failedTestCase = findTestCaseByName(junitDoc, "muzzle-fail")
    assertNotNull(failedTestCase)
    val failureNode = failedTestCase.getElementsByTagName("failure").item(0) as Element
    assertEquals("Muzzle validation failed", failureNode.getAttribute("message"))
    assertEquals("java.lang.IllegalStateException: broken helper", failureNode.textContent)
  }

  @Test
  fun `legacy report keeps historical shape`() {
    val legacySuite = legacyDoc.documentElement
    assertEquals("testsuite", legacySuite.tagName)
    assertEquals("1", legacySuite.getAttribute("tests"))
    assertEquals("0", legacySuite.getAttribute("id"))
    assertEquals("muzzle-end", legacySuite.getAttribute("name"))
    assertEquals(1, legacySuite.getElementsByTagName("testcase").length)
  }

  private fun parseXml(xml: String): Document {
    val builderFactory = DocumentBuilderFactory.newInstance()
    builderFactory.isNamespaceAware = false
    builderFactory.isIgnoringComments = true
    return builderFactory.newDocumentBuilder().parse(xml.byteInputStream())
  }

  private fun findTestCaseByName(document: Document, name: String): Element {
    val testCases = document.getElementsByTagName("testcase")
    for (idx in 0 until testCases.length) {
      val testCase = testCases.item(idx) as Element
      if (testCase.getAttribute("name") == name) {
        return testCase
      }
    }
    throw IllegalStateException("Could not find testcase with name '$name'")
  }
}
