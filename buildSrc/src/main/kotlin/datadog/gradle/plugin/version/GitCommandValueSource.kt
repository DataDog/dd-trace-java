package datadog.gradle.plugin.version

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import javax.inject.Inject

abstract class GitCommandValueSource @Inject constructor(
  private val execOperations: ExecOperations
) : ValueSource<String, GitCommandValueSource.Parameters> {
  override fun obtain(): String {
    val workDir = parameters.workingDirectory.get()
    val commands = parameters.gitCommand.get()

    val outputStream = ByteArrayOutputStream()
    val result = try {
      execOperations.exec {
        commandLine(commands)
        workingDir(workDir)
        standardOutput = outputStream
        errorOutput = outputStream
      }
    } catch (e: Exception) {
      throw GradleException("Failed to run: ${commands.joinToString(" ")}", e)
    }

    val output = outputStream.toString(Charset.defaultCharset().name())
    result.exitValue.let { exitValue ->
      if (exitValue != 0) {
        throw GradleException(
          """
          Failed to run: ${commands.joinToString(" ")}
            (exit code: $exitValue)
          Output:
          $output
          """.trimIndent()
        )
      }
    }

    return output.trim()
  }

  interface Parameters : ValueSourceParameters {
    val workingDirectory: DirectoryProperty
    val gitCommand: ListProperty<String>
  }
}
