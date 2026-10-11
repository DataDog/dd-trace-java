package datadog.gradle.plugin.muzzle

import datadog.gradle.plugin.muzzle.MuzzleAction.Companion.bootAndToolClassLoaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MuzzleActionTest {

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `bootstrap classloader only exposes the requested class-path`() {
    val base = classPathEntry("base")
    val otel = classPathEntry("otel")
    val tooling = listOf(classPathEntry("tooling"))
    val buildStamp = nextBuildStamp()

    // A larger, unrelated bootstrap class-path checked first in the same build, like
    // graal-native-image-20.0 adding agent-installer before opentelemetry-1.47 adds otel-bootstrap
    bootAndToolClassLoaders(buildStamp, listOf(base, classPathEntry("installer"), classPathEntry("installer-deps")), tooling)
    val (bootCL, toolCL) = bootAndToolClassLoaders(buildStamp, listOf(base, otel), tooling)

    assertThat(bootCL.getResource("otel.marker")).isNotNull()
    assertThat(bootCL.getResource("installer.marker")).isNull()
    assertThat(toolCL.getResource("otel.marker")).isNotNull()
    assertThat(toolCL.getResource("tooling.marker")).isNotNull()
  }

  @Test
  fun `classloaders are reused for the same class-path within a build`() {
    val bootstrap = listOf(classPathEntry("base"))
    val tooling = listOf(classPathEntry("tooling"))
    val buildStamp = nextBuildStamp()

    val classLoaders = bootAndToolClassLoaders(buildStamp, bootstrap, tooling)

    assertThat(bootAndToolClassLoaders(buildStamp, bootstrap, tooling)).isSameAs(classLoaders)
    assertThat(bootAndToolClassLoaders(nextBuildStamp(), bootstrap, tooling)).isNotSameAs(classLoaders)
  }

  /** Creates a class-path directory holding a `<name>.marker` resource. */
  private fun classPathEntry(name: String): File =
    File(tempDir, name).apply {
      mkdirs()
      File(this, "$name.marker").writeText(name)
    }

  companion object {
    private val buildStamps = AtomicLong(System.currentTimeMillis())

    private fun nextBuildStamp() = buildStamps.incrementAndGet()
  }
}
