package datadog.buildlogic.tagRegistry

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.tabletest.junit.TableTest
import java.io.File

class TagRegistryGeneratorTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `generation is deterministic and removes obsolete files`() {
    val yaml = directory.conventionsFile(
      """
      trace_level:
        tags: [{dd-name: env}]
      span_types:
        base:
          abstract: true
          tags: [{dd-name: service, otel-name: service.name}]
        http.server:
          extends: base
          include: [peer]
          tags: [{dd-name: http.method, otel-name: http.request.method, span-kind-neutral: true}]
      mixins:
        common:
          applies: all
          tags: [{dd-name: span.kind}]
        peer:
          tags: [{dd-name: peer.service}]
        ci_visibility:
          applies: [test]
          tags: [{dd-name: test.name}]
      """
    )
    val output = File(directory, "generated")
    TagRegistryGenerator.generate(yaml, output)
    val first = contents(output)
    output.writeFile("obsolete.txt", "obsolete")

    TagRegistryGenerator.generate(yaml, output)

    assertThat(contents(output)).isEqualTo(first)
    assertThat(first.keys).containsExactlyInAnyOrder(
      "java/datadog/trace/api/KnownTags.java",
      "resolved-tags.txt",
      "tag-assignment.txt"
    )
    assertThat(first.getValue("resolved-tags.txt"))
      .contains("http.server  (4 tags):", "  - service", "  - peer.service", "  - span.kind", "ci_visibility -> test")
    assertThat(first.getValue("tag-assignment.txt"))
      .contains("tags=6", "test.name", "service.name")
    assertThat(first.getValue("java/datadog/trace/api/KnownTags.java"))
      .contains("ENV_ID = 0x0001000000000004L", "TEST_NAME", "http.request.method")
  }

  @TableTest(
    """
          scenario                 | tag                               | message
          missing Datadog name     | '{type: string}'                  | no valid dd-name
          empty Datadog name       | "{dd-name: ''}"                   | no valid dd-name
          nonstring Datadog name   | '{dd-name: 42}'                   | no valid dd-name
          null OpenTelemetry name  | '{dd-name: foo, otel-name: null}' | invalid otel-name
          empty OpenTelemetry name | "{dd-name: foo, otel-name: ''}"   | invalid otel-name
          nonstring type           | '{dd-name: foo, type: 42}'        | type must be a string
          nonstring required       | '{dd-name: foo, required: 42}'    | required must be a string
          """
  )
  fun `invalid declarations preserve previous output`(tag: String, message: String) {
    val yaml = directory.conventionsFile("span_types: {base: {tags: [{dd-name: valid}]}}")
    val output = File(directory, "generated")
    TagRegistryGenerator.generate(yaml, output)
    val previous = contents(output)
    directory.conventionsFile("span_types: {base: {tags: [$tag]}}")

    assertThatIllegalArgumentException().isThrownBy { TagRegistryGenerator.generate(yaml, output) }
      .withMessageContaining(message)

    assertThat(contents(output)).isEqualTo(previous)
  }

  @TableTest(
    """
          scenario                | tags                                                                    | message
          alias matches canonical | '[{dd-name: foo, otel-name: bar}, {dd-name: bar}]'                      | collides with canonical
          alias matches own name  | '[{dd-name: foo, otel-name: foo}]'                                      | collides with canonical
          alias shared by tags    | '[{dd-name: foo, otel-name: alias}, {dd-name: bar, otel-name: alias}]'  | claimed by both
          duplicate declaration   | '[{dd-name: foo, otel-name: first}, {dd-name: foo, otel-name: second}]' | declared in both
          """
  )
  fun `duplicate declarations and ambiguous OpenTelemetry names are rejected`(tags: String, message: String) {
    val yaml = directory.conventionsFile("span_types: {base: {abstract: true, tags: $tags}}")

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining(message)
  }

  @TableTest(
    """
          scenario                     | domain                                                 | message
          self inheritance             | span_types: {base: {extends: base}}                    | cyclic extends
          cyclic inheritance           | span_types: {a: {extends: b}, b: {extends: a}}         | cyclic extends
          unknown parent               | span_types: {base: {extends: missing}}                 | extends unknown span type 'missing'
          unknown mixin                | span_types: {base: {include: [missing]}}               | includes unknown mixin 'missing'
          null span type               | span_types: {base: null}                               | span type 'base' must be a mapping
          null mixin                   | mixins: {peer: null}                                   | mixin 'peer' must be a mapping
          scalar applies               | mixins: {peer: {applies: test}}                        | applies must be 'all' or a list
          numeric applies              | mixins: {peer: {applies: 42}}                          | applies must be 'all' or a list
          nonstring applies            | mixins: {peer: {applies: [42]}}                        | applies must be 'all' or a list
          nonmapping span types        | span_types: []                                         | span_types must be a mapping
          nonmapping mixins            | mixins: []                                             | mixins must be a mapping
          nonmapping trace level       | trace_level: []                                        | trace_level must be a mapping
          scalar include               | span_types: {base: {include: peer}}                    | include must be a list of mixin names
          nonstring include            | span_types: {base: {include: [42]}}                    | include must be a list of mixin names
          list parent                  | span_types: {base: {extends: [base]}}                  | extends must be a span type name
          numeric parent               | span_types: {base: {extends: 42}}                      | extends must be a span type name
          string abstract              | span_types: {base: {abstract: 'true'}}                 | abstract must be a boolean
          mapping tags                 | span_types: {base: {tags: {dd-name: x}}}               | tags must be a list of tag declarations
          scalar tag                   | span_types: {base: {tags: [foo]}}                      | tag declaration must be a mapping
          empty reference              | span_types: {base: {tags: [{ref: ''}]}}                | ref has no valid tag name
          nonstring reference required | span_types: {base: {tags: [{ref: foo, required: 42}]}} | required must be a string
          scalar trace tag             | trace_level: {tags: [foo]}                             | tag declaration must be a mapping
          trace reference              | trace_level: {tags: [{ref: foo}]}                      | trace_level tags must be declarations
          """
  )
  fun `invalid composition preserves previous output`(domain: String, message: String) {
    val yaml = directory.conventionsFile("span_types: {base: {tags: [{dd-name: valid}]}}")
    val output = File(directory, "generated")
    TagRegistryGenerator.generate(yaml, output)
    val previous = contents(output)
    directory.conventionsFile(domain)

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, output) }
      .withMessageContaining(message)

    assertThat(contents(output)).isEqualTo(previous)
  }

  @ParameterizedTest
  @ValueSource(strings = ["url.full", "url.path"])
  fun `duplicate declarations across groups are rejected even when they agree`(otelName: String) {
    val yaml = directory.conventionsFile(
      """
      span_types:
        client:
          tags: [{dd-name: http.url, otel-name: url.full}]
        server:
          tags: [{dd-name: http.url, otel-name: $otelName}]
      """
    )

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining("declared in both 'client' and 'server'")
  }

  @Test
  fun `ref overrides only the requirement level on the referencing type`() {
    val conv = tagConventions(
      """
      span_types:
        base:
          abstract: true
          tags: [{dd-name: http.url, type: string, required: required, otel-name: url.full}]
        server:
          extends: base
        client:
          extends: base
          tags: [{ref: http.url, required: conditional}]
      """
    )

    val onClient = conv.resolve("client").single { it.name == "http.url" }
    val onServer = conv.resolve("server").single { it.name == "http.url" }
    assertThat(onClient.required).isEqualTo("conditional")
    assertThat(onClient.otelName).isEqualTo("url.full")
    assertThat(onServer.required).isEqualTo("required")
    assertThat(conv.allDeclaredTags().map { it.name }).containsExactly("http.url")
  }

  @Test
  fun `ref adds a tag declared by an unrelated span type`() {
    val conv = tagConventions(
      """
      span_types:
        server:
          tags: [{dd-name: http.url, type: string, required: required}]
        client:
          tags: [{ref: http.url}]
      """
    )

    assertThat(conv.resolve("client").map { it.name to it.required })
      .containsExactly("http.url" to "required")
  }

  @TableTest(
    """
          scenario                  | tags                                                                        | message
          reference overrides alias | '[{dd-name: http.url, type: string}, {ref: http.url, otel-name: url.full}]' | may override only `required`
          undeclared reference      | '[{ref: http.url}]'                                                         | refs undeclared tag 'http.url'
          """
  )
  fun `invalid refs are rejected`(tags: String, message: String) {
    val yaml = directory.conventionsFile("span_types: {client: {tags: $tags}}")

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining(message)
  }

  @Test
  fun `rename on a concrete span type requires span-kind-neutral`() {
    val yaml = directory.conventionsFile(
      """
      span_types:
        http.server:
          tags: [{dd-name: http.hostname, otel-name: server.address}]
      """
    )

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining("on concrete span type 'http.server'")
      .withMessageContaining("span-kind-neutral")
  }

  @Test
  fun `rename on a concrete span type passes when marked span-kind-neutral`() {
    val yaml = directory.conventionsFile(
      """
      span_types:
        db.client:
          tags: [{dd-name: db.type, otel-name: db.system, span-kind-neutral: true}]
      """
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    assertThat(contents(output).getValue("java/datadog/trace/api/KnownTags.java"))
      .contains("public static final String DB_TYPE_OTEL_NAME = \"db.system\";")
  }

  @Test
  fun `rename in a shared scope needs no span-kind-neutral`() {
    val yaml = directory.conventionsFile(
      """
      span_types:
        http:
          abstract: true
          tags: [{dd-name: http.method, otel-name: http.request.method}]
        http.server:
          extends: http
      mixins:
        peer:
          tags: [{dd-name: peer.port, type: int, otel-name: server.port}]
      """
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    assertThat(contents(output).getValue("java/datadog/trace/api/KnownTags.java"))
      .contains("HTTP_METHOD_OTEL_NAME", "PEER_PORT_OTEL_NAME")
  }

  @Test
  fun `span-kind-neutral without an otel-name fails`() {
    val yaml = directory.conventionsFile(
      """
      span_types:
        web:
          tags: [{dd-name: http.route, span-kind-neutral: true}]
      """
    )

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining("span-kind-neutral without an otel-name")
  }

  private fun tagConventions(@Language("yaml") yamlText: String) = TagConventions.parse(
    ObjectMapper(YAMLFactory()).readValue(
      directory.conventionsFile(yamlText),
      object : TypeReference<Map<String, Any?>>() {}
    )
  )

  private fun contents(output: File): Map<String, String> = output.walkTopDown().filter { it.isFile }
    .associate {
      it.relativeTo(output).invariantSeparatorsPath to it.readText()
    }
}
