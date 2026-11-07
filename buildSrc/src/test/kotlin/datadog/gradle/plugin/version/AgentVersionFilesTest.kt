package datadog.gradle.plugin.version

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class AgentVersionFilesTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `accepts exactly the expected version resources and stamp`() {
    assertThat(validate(mapOf("agent.version" to VERSION, "cws-tls/cws-tls.version" to VERSION)))
      .isEmpty()
  }

  @Test
  fun `reports missing and unexpected version resources`() {
    assertThat(validate(mapOf("agent.version" to VERSION, "other.version" to VERSION)))
      .containsExactly(
        "Missing version resource: cws-tls/cws-tls.version",
        "Unexpected version resource: other.version",
      )
  }

  @Test
  fun `reports incorrect version values and stale commit stamps`() {
    assertThat(validate(mapOf("agent.version" to "2.0.0~0123456789", "cws-tls/cws-tls.version" to "1.2.3~abcdef0123")))
      .containsExactly(
        "Incorrect version resource: agent.version (expected $VERSION)",
        "Incorrect version resource: cws-tls/cws-tls.version (expected $VERSION)",
      )
  }

  private fun validate(entries: Map<String, String>): List<String> {
    val file = File(directory, "agent.jar")
    JarOutputStream(file.outputStream()).use { jar ->
      (entries + ("agent.classdata" to "implementation")).forEach { (path, content) ->
        jar.putNextEntry(JarEntry(path))
        jar.write(content.toByteArray(Charsets.UTF_8))
        jar.closeEntry()
      }
    }
    return JarFile(file).use { jar ->
      AgentVersionFiles.validate(jar, setOf("agent.version", "cws-tls/cws-tls.version"), VERSION)
    }
  }

  companion object {
    private const val VERSION = "1.2.3~0123456789"
  }
}
