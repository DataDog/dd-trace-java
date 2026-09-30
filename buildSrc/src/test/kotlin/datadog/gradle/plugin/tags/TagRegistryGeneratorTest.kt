package datadog.gradle.plugin.tags

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
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

  private fun parse(content: String): TagConventions =
    TagConventions.parse(
      ObjectMapper(YAMLFactory())
        .readValue(content.trimIndent(), object : TypeReference<Map<String, Any?>>() {}))

  @Test
  fun `emits name, id and OpenTelemetry name constants`() {
    generate(
      """
      span_types:
        web:
          tags:
            - { dd-name: http.method, type: string, required: required, otel-name: http.request.method, span-kind-neutral: true }
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
            - { dd-name: http.method, type: string, required: required, otel-name: http.request.method, span-kind-neutral: true }
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
  fun `declaring a tag twice fails even when the declarations agree`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            client:
              tags:
                - { dd-name: http.url, type: string, otel-name: url.full }
            server:
              tags:
                - { dd-name: http.url, type: string, otel-name: url.full }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("declared in both 'client' and 'server'")
  }

  @Test
  fun `ref overrides only the requirement level on the referencing type`() {
    val conv =
      parse(
        """
        span_types:
          base:
            abstract: true
            tags:
              - { dd-name: http.url, type: string, required: required, otel-name: url.full }
          server:
            extends: base
          client:
            extends: base
            tags:
              - { ref: http.url, required: conditional }
        """)

    val onClient = conv.resolve("client").single { it.name == "http.url" }
    val onServer = conv.resolve("server").single { it.name == "http.url" }
    assertThat(onClient.required).isEqualTo("conditional")
    assertThat(onClient.otelName).isEqualTo("url.full")
    assertThat(onServer.required).isEqualTo("required")
    assertThat(conv.allDeclaredTags().map { it.name }).containsExactly("http.url")
  }

  @Test
  fun `ref adds a tag declared by an unrelated span type`() {
    val conv =
      parse(
        """
        span_types:
          server:
            tags:
              - { dd-name: http.url, type: string, required: required }
          client:
            tags:
              - { ref: http.url }
        """)

    assertThat(conv.resolve("client").map { it.name to it.required })
      .containsExactly("http.url" to "required")
  }

  @Test
  fun `ref that sets anything but required fails`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            server:
              tags:
                - { dd-name: http.url, type: string }
            client:
              tags:
                - { ref: http.url, otel-name: url.full }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("may override only `required`")
  }

  @Test
  fun `ref to an undeclared tag fails`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            client:
              tags:
                - { ref: http.url }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("refs undeclared tag 'http.url'")
  }

  @Test
  fun `rename on a concrete span type requires span-kind-neutral`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            http.server:
              tags:
                - { dd-name: http.hostname, type: string, otel-name: server.address }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("on concrete span type 'http.server'")
      .hasMessageContaining("span-kind-neutral")
  }

  @Test
  fun `rename on a concrete span type passes when marked span-kind-neutral`() {
    generate(
      """
      span_types:
        db.client:
          tags:
            - { dd-name: db.type, type: string, otel-name: db.system, span-kind-neutral: true }
      """)

    assertThat(knownTags.readText())
      .contains("public static final String DB_TYPE_OTEL_NAME = \"db.system\";")
  }

  @Test
  fun `rename in a shared scope needs no span-kind-neutral`() {
    generate(
      """
      span_types:
        http:
          abstract: true
          tags:
            - { dd-name: http.method, type: string, otel-name: http.request.method }
        http.server:
          extends: http
      mixins:
        peer:
          tags:
            - { dd-name: peer.port, type: int, otel-name: server.port }
      """)

    assertThat(knownTags.readText())
      .contains("HTTP_METHOD_OTEL_NAME")
      .contains("PEER_PORT_OTEL_NAME")
  }

  @Test
  fun `span-kind-neutral without an otel-name fails`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            web:
              tags:
                - { dd-name: http.route, type: string, span-kind-neutral: true }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("span-kind-neutral without an otel-name")
  }

  @Test
  fun `otel-name that is another tag's dd-name fails`() {
    assertThatThrownBy {
        generate(
          """
          span_types:
            web:
              tags:
                - { dd-name: a, type: string, otel-name: b, span-kind-neutral: true }
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
                - { dd-name: a, type: string, otel-name: c, span-kind-neutral: true }
                - { dd-name: b, type: string, otel-name: c, span-kind-neutral: true }
          """)
      }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("claimed by both")
  }
}
