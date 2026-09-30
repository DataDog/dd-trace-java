package datadog.gradle.plugin.tags

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TagRegistryGeneratorTest {
  @TempDir lateinit var dir: File

  private val outDir: File get() = File(dir, "generated")
  private val knownTags: File get() = File(outDir, "java/datadog/trace/api/KnownTags.java")

  private fun yaml(content: String): File =
    File(dir, "tag-conventions.yaml").apply { writeText(content.trimIndent() + "\n") }

  private fun generate(content: String) = TagRegistryGenerator.generate(yaml(content), outDir)

  @Test
  fun `emits name, id and OpenTelemetry name constants`() {
    generate(
      """
      span_types:
        web:
          tags:
            - { dd-name: http.method, type: string, required: required, otel-name: http.request.method }
            - { dd-name: http.route,  type: string, required: conditional }
      """)

    val source = knownTags.readText()
    assertThat(source)
      .contains("public static final String HTTP_METHOD_NAME = \"http.method\";")
      .contains("public static final String HTTP_METHOD_OTEL_NAME = \"http.request.method\";")
      .contains("public static final String HTTP_ROUTE_NAME = \"http.route\";")
      .contains("HTTP_METHOD_ID")
      .doesNotContain("HTTP_ROUTE_OTEL_NAME")
    assertThat(File(outDir, "tag-assignment.txt").readText())
      .contains("http.request.method            -> http.method")
    assertThat(File(outDir, "resolved-tags.txt")).exists()
  }

  @Test
  fun `generation is deterministic`() {
    val content =
      """
      trace_level:
        tags:
          - { dd-name: env, type: string, required: recommended }
      span_types:
        web:
          tags:
            - { dd-name: http.method, type: string, required: required, otel-name: http.request.method }
      """
    generate(content)
    val first = outDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(outDir).path to it.readText() }
    generate(content)
    val second = outDir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(outDir).path to it.readText() }

    assertThat(second).isEqualTo(first)
  }

  @Test
  fun `constant names stay unique when suffixing collapses two tags together`() {
    generate(
      """
      span_types:
        web:
          tags:
            - { dd-name: foo,      type: string }
            - { dd-name: foo.name, type: string }
      """)

    val names =
      Regex("""public static final \w+ (\w+) =""")
        .findAll(knownTags.readText())
        .map { it.groupValues[1] }
        .toList()
    assertThat(names).doesNotHaveDuplicates()
    assertThat(names).contains("FOO_NAME", "FOO_NAME_2")
  }

  @Test
  fun `missing dd-name fails without deleting the previous output`() {
    generate(
      """
      span_types:
        web:
          tags:
            - { dd-name: http.route, type: string }
      """)
    val previous = knownTags.readText()

    assertThatThrownBy {
        generate(
          """
          span_types:
            web:
              tags:
                - { dd-nam: http.route, type: string }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("dd-name")
    assertThat(knownTags.readText()).isEqualTo(previous)
  }

  @Test
  fun `conflicting otel-name across span types fails`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            client:
              tags:
                - { dd-name: http.url, type: string, otel-name: url.full }
            server:
              tags:
                - { dd-name: http.url, type: string, otel-name: url.path }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("conflicting otel-name")
  }

  @Test
  fun `otel-name that is another tag's dd-name fails`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            web:
              tags:
                - { dd-name: a, type: string, otel-name: b }
                - { dd-name: b, type: string }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("collides with canonical tag name")
  }

  @Test
  fun `otel-name claimed by two tags fails`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            web:
              tags:
                - { dd-name: a, type: string, otel-name: c }
                - { dd-name: b, type: string, otel-name: c }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("claimed by both")
  }
}
