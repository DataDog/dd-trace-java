package datadog.gradle.plugin.version

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import javax.inject.Inject

abstract class GitDescribeValueSource @Inject constructor(
  private val execOperations: ExecOperations
) : ValueSource<String, GitDescribeValueSource.Parameters> {
  override fun obtain(): String {
    val workDir = parameters.workingDirectory.get()
    val tagPrefix = parameters.tagVersionPrefix.get()

    val commandsArray = mutableListOf(
      "git",
      "describe",
      "--abbrev=8",
      "--tags",
      "--first-parent",
      "--match=$tagPrefix[0-9].[0-9]*.[0-9]",
    )
    if (parameters.showDirty.get()) {
      commandsArray.add("--dirty")
    }

    val outputStream = ByteArrayOutputStream()
    val result = try {
      execOperations.exec {
        commandLine(commandsArray)
        workingDir(workDir)
        standardOutput = outputStream
        errorOutput = outputStream
      }
    } catch (e: Exception) {
      throw GradleException("Failed to run: ${commandsArray.joinToString(" ")}", e)
    }

    val output = outputStream.toString(Charset.defaultCharset().name())
    result.exitValue.let { exitValue ->
      if (exitValue != 0) {
        throw GradleException(
          """
          Failed to run: ${commandsArray.joinToString(" ")}
            (exit code: $exitValue)
          Output:
          $output
          """.trimIndent()
        )
      }
    }

    return output
  }

  interface Parameters : ValueSourceParameters {
    val tagVersionPrefix: Property<String>
    val showDirty: Property<Boolean>
    val workingDirectory: DirectoryProperty
  }
}
