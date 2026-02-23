package datadog.gradle.plugin.muzzle.tasks

import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
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
import org.assertj.core.api.Assertions.assertThat

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
    assertThat(suite.tagName).isEqualTo("testsuite")
    assertThat(suite.getAttribute("name")).isEqualTo(":lettuce-5.0")
    assertThat(suite.getAttribute("tests")).isEqualTo("2")
    assertThat(suite.getAttribute("failures")).isEqualTo("1")
    assertThat(suite.getAttribute("errors")).isEqualTo("0")
    assertThat(suite.getAttribute("skipped")).isEqualTo("0")
  }

  @Test
  fun `passed testcase has no failure node`() {
    val passedTestCase = findTestCaseByName(junitDoc, "muzzle-pass")
    assertThat(passedTestCase).isNotNull()
    assertThat(passedTestCase.getElementsByTagName("failure").item(0)).isNull()
  }

  @Test
  fun `failed testcase contains failure node and message`() {
    val failedTestCase = findTestCaseByName(junitDoc, "muzzle-fail")
    assertThat(failedTestCase).isNotNull()
    val failureNode = failedTestCase.getElementsByTagName("failure").item(0) as Element
    assertThat(failureNode.getAttribute("message")).isEqualTo("Muzzle validation failed")
    assertThat(failureNode.textContent).isEqualTo("java.lang.IllegalStateException: something is broken")
  }

  @Test
  fun `legacy report keeps historical shape`() {
    val legacySuite = legacyDoc.documentElement
    assertThat(legacySuite.tagName).isEqualTo("testsuite")
    assertThat(legacySuite.getAttribute("tests")).isEqualTo("1")
    assertThat(legacySuite.getAttribute("id")).isEqualTo("0")
    assertThat(legacySuite.getAttribute("name")).isEqualTo("muzzle-end")
    assertThat(legacySuite.getElementsByTagName("testcase").length).isEqualTo(1)
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
