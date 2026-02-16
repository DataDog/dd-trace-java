package datadog.gradle.plugin.muzzle

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal class MuzzlePluginTestFixture(
  private val projectDir: File,
) {
  fun writeProject(instrumentationBuildScript: String) {
    file("settings.gradle").writeText(
      """
      rootProject.name = 'muzzle-e2e'
      include ':dd-java-agent:agent-bootstrap'
      include ':dd-java-agent:agent-tooling'
      include ':dd-java-agent:instrumentation:demo'
      """.trimIndent()
    )

    file("dd-java-agent/agent-bootstrap/build.gradle").writeText(
      """
      plugins {
        id 'java'
      }
      
      tasks.register('compileMain_java11Java')
      """.trimIndent()
    )

    file("dd-java-agent/agent-tooling/build.gradle").writeText(
      """
      plugins {
        id 'java'
      }
      """.trimIndent()
    )

    file("dd-java-agent/instrumentation/demo/build.gradle").writeText(instrumentationBuildScript.trimIndent())
  }

  fun writePassingScanPlugin() {
    writeScanPlugin("// pass")
  }

  fun writeScanPlugin(assertionBody: String) {
    file("dd-java-agent/instrumentation/demo/src/main/java/datadog/trace/agent/tooling/muzzle/MuzzleVersionScanPlugin.java")
      .writeText(
        """
        package datadog.trace.agent.tooling.muzzle;
        
        public final class MuzzleVersionScanPlugin {
          private MuzzleVersionScanPlugin() {}
        
          public static void assertInstrumentationMuzzled(
              ClassLoader instrumentationClassLoader,
              ClassLoader testApplicationClassLoader,
              boolean assertPass,
              String muzzleDirective) {
            $assertionBody
          }
        }
        """.trimIndent()
      )
  }

  fun run(vararg args: String): BuildResult =
    GradleRunner.create()
      .withTestKitDir(File(projectDir, ".gradle-test-kit"))
      .withDebug(true)
      .withPluginClasspath()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .build()

  fun findSingleMuzzleJUnitReport(): File {
    val reports = projectDir.walkTopDown()
      .filter { it.isFile && it.name.startsWith("TEST-muzzle-") && it.extension == "xml" }
      .toList()
    require(reports.size == 1) {
      "Expected exactly one JUnit muzzle report, but found ${reports.size}"
    }
    return reports.single()
  }

  fun parseXml(xmlFile: File): Document {
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    return builder.parse(xmlFile)
  }

  private fun file(path: String): File =
    File(projectDir, path).also { file ->
      file.parentFile?.mkdirs()
    }
}
