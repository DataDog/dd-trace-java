package datadog.gradle.plugin

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HostPlatformTest {
  private val repositoryDirectory = File("repository")
  private val arguments = listOf("-Dexample=true", "package")

  @Test
  fun `Maven wrapper command uses cmd on Windows`() {
    assertThat(
      HostPlatform.mavenWrapperCommand(repositoryDirectory, arguments, "Windows Server 2025")
    )
      .containsExactly(
        "cmd",
        "/c",
        File(repositoryDirectory, "mvnw.cmd").absolutePath,
        "-Dexample=true",
        "package",
      )
  }

  @Test
  fun `Maven wrapper command runs wrapper directly on Unix`() {
    assertThat(HostPlatform.mavenWrapperCommand(repositoryDirectory, arguments, "Mac OS X"))
      .containsExactly(
        File(repositoryDirectory, "mvnw").absolutePath,
        "-Dexample=true",
        "package",
      )
    assertThat(HostPlatform.mavenWrapperCommand(repositoryDirectory, arguments, "Linux"))
      .containsExactly(
        File(repositoryDirectory, "mvnw").absolutePath,
        "-Dexample=true",
        "package",
      )
  }
}
