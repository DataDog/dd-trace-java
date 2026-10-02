package datadog.gradle.plugin.observer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ObserverAgentSelectionTest {
  @Test
  fun `nothing is selected without an attached observer`() {
    assertThat(ObserverAgentSelection.attached()).isNull()
    assertThat(ObserverAgentSelection.isAttachedAgentArgument("-javaagent:/any/observer.jar")).isFalse()
    assertThatThrownBy { ObserverAgentSelection.validate() }.isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `agent arguments match the jar whatever path spelling they use`(@TempDir directory: Path) {
    val jar = Files.createFile(directory.resolve("observer.jar")).toFile()
    val link = Files.createSymbolicLink(directory.resolve("link.jar"), jar.toPath()).toFile()
    assertThat(ObserverAgentSelection.pointsAt(jar, "-javaagent:${link.path}")).isTrue()
    assertThat(ObserverAgentSelection.pointsAt(jar, "-javaagent:${jar.path}=dd.service=x")).isTrue()
    assertThat(ObserverAgentSelection.pointsAt(jar, "-javaagent:${directory.resolve("other.jar")}")).isFalse()
    assertThat(ObserverAgentSelection.pointsAt(jar, jar.path)).isFalse()
  }
}
