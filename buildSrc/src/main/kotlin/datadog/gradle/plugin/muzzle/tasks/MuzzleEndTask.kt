package datadog.gradle.plugin.muzzle.tasks

import datadog.gradle.plugin.muzzle.pathSlug
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory

abstract class MuzzleEndTask : AbstractMuzzleTask() {
  @get:Input
  abstract val startTimeMs: Property<Long>

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val muzzleResultFiles: ConfigurableFileCollection

  @get:OutputFile
  val resultsFile = project
    .layout
    .buildDirectory
    .file("test-results/muzzle/TEST-muzzle-${project.pathSlug}.xml")

  @get:OutputFile
  val legacyResultsFile = project.rootProject
    .layout
    .buildDirectory
    .file("${MUZZLE_TEST_RESULTS}/${project.pathSlug}_muzzle/results.xml")

  @TaskAction
  fun generatesResultFile() {
    val report = buildJUnitReport()
    writeReportFile(project.file(resultsFile), renderReportXml(report), "muzzle junit")
    writeReportFile(project.file(legacyResultsFile), renderLegacyReportXml(report.durationSeconds), "muzzle legacy")
  }

  private fun buildJUnitReport(): MuzzleJUnitReport {
    val endTimeMs = System.currentTimeMillis()
    val seconds = (endTimeMs - startTimeMs.get()).toDouble() / 1000.0
    val testCases = muzzleResultFiles.files
      .sortedBy { it.name }
      .map { resultFile ->
      val taskName = resultFile.name.removeSuffix(".txt")
      when {
        !resultFile.exists() -> {
          MuzzleJUnitCase(
            name = taskName,
            failureMessage = "Muzzle result file missing",
            failureText = "Expected ${resultFile.path}"
          )
        }

        resultFile.readText() == "PASSING" -> MuzzleJUnitCase(name = taskName)
        else -> {
          MuzzleJUnitCase(
            name = taskName,
            failureMessage = "Muzzle validation failed",
            failureText = resultFile.readText()
          )
        }
      }
    }
    return MuzzleJUnitReport(
      suiteName = "muzzle:${project.path}",
      module = project.path,
      className = "muzzle.${project.pathSlug}",
      durationSeconds = seconds,
      testCases = testCases
    )
  }

  private fun renderReportXml(report: MuzzleJUnitReport): String {
    val output = StringWriter()
    val xmlWriter = XMLOutputFactory.newInstance().createXMLStreamWriter(output)
    with(xmlWriter) {
      try {
        writeStartDocument("UTF-8", "1.0")
        writeCharacters("\n")
        writeStartElement("testsuite")
        writeAttribute("name", report.suiteName)
        writeAttribute("tests", report.testCases.size.toString())
        writeAttribute("failures", report.failures.toString())
        writeAttribute("errors", "0")
        writeAttribute("skipped", "0")
        writeAttribute("time", report.durationSeconds.toString())
        writeCharacters("\n")

        writeStartElement("properties")
        writeCharacters("\n")
        writeEmptyElement("property")
        writeAttribute("name", "category")
        writeAttribute("value", "muzzle")
        writeCharacters("\n")
        writeEmptyElement("property")
        writeAttribute("name", "module")
        writeAttribute("value", report.module)
        writeCharacters("\n")
        writeEndElement()
        writeCharacters("\n")

        report.testCases.forEach { testCase ->
          writeStartElement("testcase")
          writeAttribute("classname", report.className)
          writeAttribute("name", testCase.name)
          writeAttribute("time", "0")
          if (testCase.failureMessage != null) {
            writeCharacters("\n")
            writeStartElement("failure")
            writeAttribute("message", testCase.failureMessage)
            writeCharacters(testCase.failureText ?: "")
            writeEndElement()
            writeCharacters("\n")
          }
          writeEndElement()
          writeCharacters("\n")
        }
        writeEndElement()
        writeEndDocument()
        flush()
      } finally {
        close()
      }
    }
    return output.toString()
  }

  private fun writeReportFile(file: File, xml: String, label: String) {
    file.parentFile.mkdirs()
    file.writeText(xml)
    project.logger.info("Wrote $label report to\n  $file")
  }

  private fun renderLegacyReportXml(durationSeconds: Double): String {
    return """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="$name" tests="1" id="0" time="$durationSeconds">
        <testcase name="$name" time="$durationSeconds"/>
      </testsuite>
      """.trimIndent()
  }

  private data class MuzzleJUnitReport(
    val suiteName: String,
    val module: String,
    val className: String,
    val durationSeconds: Double,
    val testCases: List<MuzzleJUnitCase>
  ) {
    val failures: Int
      get() = testCases.count { it.failureMessage != null }
  }

  private data class MuzzleJUnitCase(
    val name: String,
    val failureMessage: String? = null,
    val failureText: String? = null
  )

  companion object {
    private const val MUZZLE_TEST_RESULTS = "muzzle-test-results"
  }
}
