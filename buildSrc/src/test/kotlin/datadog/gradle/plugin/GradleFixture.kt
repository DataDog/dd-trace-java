package datadog.gradle.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildResultException
import org.intellij.lang.annotations.Language
import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Base fixture for Gradle plugin integration tests.
 * Provides common functionality for setting up test projects and running Gradle builds.
 */
internal open class GradleFixture(protected val projectDir: File) {
  /**
   * Runs Gradle with the specified arguments.
   *
   * @param args Gradle task names and arguments
   * @param expectFailure Whether the build is expected to fail
   * @param env Environment variables to set (merged with system environment)
   * @return The build result
   */
  fun run(vararg args: String, expectFailure: Boolean = false, env: Map<String, String> = emptyMap()): BuildResult {
    val runner = GradleRunner.create()
      .withTestKitDir(File(projectDir, ".gradle-test-kit"))
      .withPluginClasspath()
      .withProjectDir(projectDir)
      .withEnvironment(System.getenv() + env)
      .withArguments(*args)
    return try {
      if (expectFailure) runner.buildAndFail() else runner.build()
    } catch (e: UnexpectedBuildResultException) {
      e.buildResult
    }
  }

  /**
   * Adds a subproject to the build.
   * Updates settings.gradle and creates the build script for the subproject.
   *
   * @param projectPath The project path (e.g., "dd-java-agent:instrumentation:other")
   * @param buildScript The build script content for the subproject
   */
  fun addSubproject(projectPath: String, @Language("Groovy") buildScript: String) {
    // Add to settings.gradle
    val settingsFile = file("settings.gradle")
    if (settingsFile.exists()) {
      settingsFile.appendText("\ninclude ':$projectPath'")
    } else {
      settingsFile.writeText("include ':$projectPath'")
    }

    file("${projectPath.replace(':', '/')}/build.gradle")
      .writeText(buildScript.trimIndent())
  }

  /**
   * Writes the root project's build.gradle file.
   *
   * @param buildScript The build script content for the root project
   */
  fun writeRootProject(@Language("Groovy") buildScript: String) {
    file("build.gradle").writeText(buildScript.trimIndent())
  }

  /**
   * Parses an XML file into a DOM Document.
   */
  fun parseXml(xmlFile: File): Document {
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    return builder.parse(xmlFile)
  }

  /**
   * Creates or gets a file in the project directory, ensuring parent directories exist.
   */
  protected fun file(path: String): File =
    File(projectDir, path).also { file ->
      file.parentFile?.mkdirs()
    }
}
