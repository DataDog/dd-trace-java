package datadog.gradle.plugin.testing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GitLabCollapsedSectionTest {
  @Test
  fun `section is collapsed and wraps the body between matching markers`() {
    assertThat(gitLabCollapsedSection("name", 42, "Title", "line 1\nline 2")).isEqualTo(
      "\u001B[0Ksection_start:42:name[collapsed=true]\r\u001B[0KTitle\n" +
        "line 1\nline 2\n" +
        "\u001B[0Ksection_end:42:name\r\u001B[0K"
    )
  }

  @Test
  fun `plain section keeps the body expanded`() {
    assertThat(plainSection("Title", "body")).isEqualTo("Title\nbody")
  }
}
