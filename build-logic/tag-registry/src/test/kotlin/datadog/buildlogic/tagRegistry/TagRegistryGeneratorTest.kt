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
      .contains("ENV = 0x0001000000000004L", "TEST_NAME", "http.request.method")
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
          unknown span kind            | span_types: {base: {span-kind: sideways}}              | span-kind must be one of
          unknown mixin span kind      | mixins: {peer: {span-kind: sideways}}                  | span-kind must be one of
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
  fun `rename on a concrete span type with no span-kind requires span-kind-neutral`() {
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
  fun `rename on a span type with a span-kind applies only in its direction`() {
    val yaml = directory.conventionsFile(
      """
      span_types:
        http.server:
          span-kind: server
          tags: [{dd-name: http.hostname, otel-name: server.address}]
      """
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    val generated = contents(output)
    val source = generated.getValue("java/datadog/trace/api/KnownTags.java")
    assertThat(source).doesNotContain("HTTP_HOSTNAME_OTEL_NAME")
    assertThat(source.substringAfter("IDOF_NAMES = {").substringBefore("};")).doesNotContain("server.address")
    assertThat(source).contains("return direction == KnownTagCodec.DIRECTION_INBOUND ? \"server.address\" : null;")
    assertThat(generated.getValue("tag-assignment.txt"))
      .containsPattern("server\\.address +inbound +-> http\\.hostname")
  }

  @Test
  fun `one OpenTelemetry name can map to a different tag in each direction`() {
    val conventions = tagConventions(
      """
      span_types:
        http.server:
          span-kind: server
          include: [inbound_peer]
          tags: [{dd-name: http.hostname, otel-name: server.address}]
        http.client:
          span-kind: client
          include: [outbound_peer]
      mixins:
        outbound_peer:
          span-kind: client
          tags: [{dd-name: peer.hostname, otel-name: server.address}]
        inbound_peer:
          span-kind: server
      """
    )

    val tags = TagRegistry.build(conventions).tags.associateBy { it.name }

    assertThat(tags.getValue("http.hostname").scopedOtelNames)
      .isEqualTo(mapOf(TagConventions.Direction.INBOUND to "server.address"))
    assertThat(tags.getValue("peer.hostname").scopedOtelNames)
      .isEqualTo(mapOf(TagConventions.Direction.OUTBOUND to "server.address"))
    assertThat(tags.values.map { it.otelName }).containsOnlyNulls()
  }

  @Test
  fun `each direction gets its OpenTelemetry names as the ids of the tags they name`() {
    val yaml = directory.conventionsFile(
      """
      concepts:
        - { concept: server.port, type: int, dd-name: server.port, otel-name: server.port }
      span_types:
        base: {abstract: true, tags: [{dd-name: http.method, otel-name: http.request.method}]}
        http.server:
          span-kind: server
          tags:
            - {dd-name: http.hostname, otel-name: server.address}
            - {concept-ref: server.port}
        http.client: {span-kind: client, include: [outbound_peer]}
      mixins:
        outbound_peer:
          span-kind: client
          tags:
            - {dd-name: peer.hostname, otel-name: server.address}
      """
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    val source = contents(output).getValue("java/datadog/trace/api/KnownTags.java")
    fun holder(name: String) = source.substringAfter("public static final class $name {").substringBefore("private $name() {}")
    assertThat(holder("Outbound"))
      .contains("SERVER_ADDRESS = KnownTags.PEER_HOSTNAME;", "HTTP_REQUEST_METHOD = KnownTags.HTTP_METHOD;")
    assertThat(holder("Inbound"))
      .contains("SERVER_ADDRESS = KnownTags.HTTP_HOSTNAME;", "HTTP_REQUEST_METHOD = KnownTags.HTTP_METHOD;")
    assertThat(holder("Internal")).doesNotContain("SERVER_ADDRESS")
    // A concept's names are shared, so it has no alias: instrumentation writes its id directly.
    assertThat(source.substringAfter("public static final class Inbound {")).doesNotContain("= KnownTags.SERVER_PORT;")
  }

  /** The ports, as concepts: the shape of tag-conventions.yaml's `concepts:` section. */
  private val portConcepts =
    """
    concepts:
      - { concept: server.port,      type: int, dd-name: server.port, otel-name: server.port }
      - { concept: peer.server.port, type: int, dd-name: peer.port,   otel-name: server.port }
      - { concept: client.port,      type: int, dd-name: peer.port,   otel-name: client.port }
    span_types:
      http.client: {span-kind: client, include: [outbound_peer]}
      http.server: {span-kind: server, include: [inbound_peer], tags: [{concept-ref: server.port}]}
      messaging.producer: {span-kind: producer, tags: [{concept-ref: peer.server.port}]}
      messaging.consumer: {span-kind: consumer, tags: [{concept-ref: peer.server.port}]}
    mixins:
      outbound_peer: {span-kind: client, tags: [{concept-ref: peer.server.port}]}
      inbound_peer: {span-kind: server, tags: [{concept-ref: client.port}]}
    """

  @Test
  fun `a concept has fixed names and resolves on the kinds of the span types that carry it`() {
    val conventions = tagConventions(portConcepts)
    val declared = conventions.allDeclaredTags().single { it.name == "peer.server.port" }
    val peerServerPort = TagRegistry.build(conventions).tags.single { it.name == "peer.server.port" }

    assertThat(declared.concept).isTrue()
    assertThat(peerServerPort.datadogName).isEqualTo("peer.port")
    assertThat(peerServerPort.otelName).isEqualTo("server.port")
    assertThat(peerServerPort.resolvesOn).containsExactlyInAnyOrder(
      TagConventions.SpanKind.CLIENT,
      TagConventions.SpanKind.PRODUCER,
      TagConventions.SpanKind.CONSUMER,
    )
    assertThat(conventions.resolve("http.server").map { it.name }).contains("server.port", "client.port")
    assertThat(conventions.resolve("http.client").map { it.name }).contains("peer.server.port")
  }

  @Test
  fun `two concepts can share a name, which resolves by kind`() {
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(directory.conventionsFile(portConcepts), output)

    val source = contents(output).getValue("java/datadog/trace/api/KnownTags.java")
    // Id to name is a constant: both share peer.port.
    assertThat(source).contains(
      "PEER_SERVER_PORT_NAME = \"peer.port\"",
      "CLIENT_PORT_NAME = \"peer.port\"",
      "SERVER_PORT_NAME = \"server.port\"",
      "CONCEPTS_NAMED_PEER_PORT = {CLIENT_PORT, PEER_SERVER_PORT};",
    )
    assertThat(source.substringAfter("IDOF_NAMES = {").substringBefore("};"))
      .doesNotContain("SERVER_PORT_NAME", "CLIENT_PORT_NAME")
    val byName = source.substringAfter("public long datadogIdOf(String name, int spanKind)").substringBefore("@Override")
    assertThat(byName.substringAfter("case \"peer.port\":").substringBefore("default:")).contains(
      "case KnownTagCodec.KIND_SERVER:\n            return CLIENT_PORT;",
      "case KnownTagCodec.KIND_CLIENT:\n            return PEER_SERVER_PORT;",
    )
  }

  @Test
  fun `a shared name must resolve to one concept on each kind`() {
    assertThatIllegalArgumentException()
      .isThrownBy {
        TagRegistry.build(
          tagConventions(
            """
            concepts:
              - { concept: a.port, type: int, dd-name: peer.port, otel-name: a.port }
              - { concept: b.port, type: int, dd-name: peer.port, otel-name: b.port }
            span_types:
              http.client: {span-kind: client, tags: [{concept-ref: a.port}]}
              db.client: {span-kind: client, tags: [{concept-ref: b.port}]}
            """
          )
        )
      }
      .withMessageContaining("Datadog name 'peer.port' resolves to both 'a.port' and 'b.port' on client spans")
  }

  @Test
  fun `a concept no kinded span type carries fails generation`() {
    assertThatIllegalArgumentException()
      .isThrownBy {
        TagRegistry.build(
          tagConventions(
            """
            concepts:
              - { concept: a.port, type: int, dd-name: peer.port, otel-name: a.port }
            span_types:
              misc: {tags: [{concept-ref: a.port}]}
            """
          )
        )
      }
      .withMessageContaining("is carried by no span type with a span-kind")
  }

  @Test
  fun `resolves-on is not declared, it follows from concept-refs`() {
    assertThatIllegalArgumentException()
      .isThrownBy {
        tagConventions("concepts: [{ concept: p, dd-name: peer.port, otel-name: server.port, resolves-on: [client] }]")
      }
      .withMessageContaining("follow from the span types that concept-ref it")
  }

  @Test
  fun `a concept's name cannot also be another tag's name`() {
    assertThatIllegalArgumentException()
      .isThrownBy {
        TagRegistry.build(
          tagConventions(
            """
            concepts:
              - { concept: peer.server.port, type: int, dd-name: peer.port, otel-name: server.port }
            span_types:
              http.client: {span-kind: client, tags: [{concept-ref: peer.server.port}]}
            mixins:
              other: {tags: [{ dd-name: peer.port, type: int }]}
            """
          )
        )
      }
      .withMessageContaining("is also the name of another tag")
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "{ concept: p, otel-name: server.port }",
      "{ concept: p, dd-name: peer.port }",
      "{ concept: p, dd-name: { client: peer.port }, otel-name: server.port }",
      "{ concept: p, dd-name: peer.port, otel-name: server.port, span-kind-neutral: true }",
      "{ concept: p, dd-name: peer.port, otel-name: server.port, sideways: true }",
    ]
  )
  fun `a malformed concept fails generation`(declaration: String) {
    assertThatIllegalArgumentException().isThrownBy {
      tagConventions("concepts: [$declaration]")
    }
  }

  @Test
  fun `a concept declared outside the concepts section fails generation`() {
    assertThatIllegalArgumentException()
      .isThrownBy {
        tagConventions("mixins: {ports: {tags: [{ concept: p, dd-name: peer.port, otel-name: server.port }]}}")
      }
      .withMessageContaining("top-level `concepts:` section")
  }

  @Test
  fun `a name declared per direction fails generation and asks for concepts`() {
    assertThatIllegalArgumentException()
      .isThrownBy {
        tagConventions(
          """
          mixins:
            outbound_peer: {span-kind: client, tags: [{dd-name: peer.port, type: int}]}
            inbound_peer: {span-kind: server, tags: [{dd-name: peer.port, type: int}]}
          """
        )
      }
      .withMessageContaining("'peer.port' is declared once per direction")
      .withMessageContaining("concepts:")
  }

  @Test
  fun `a Datadog name that is another tag's OpenTelemetry name fails generation`() {
    assertThatIllegalArgumentException()
      .isThrownBy {
        TagRegistry.build(
          tagConventions(
            """
            span_types:
              http.server: {span-kind: server, tags: [{dd-name: server.port, type: int}]}
            mixins:
              outbound_peer: {span-kind: client, tags: [{dd-name: peer.port, type: int, otel-name: server.port}]}
            """
          )
        )
      }
      .withMessageContaining("'server.port' is a Datadog name, and also another tag's OpenTelemetry name")
  }

  @Test
  fun `a concept is referenced with concept-ref, not ref`() {
    assertThatIllegalArgumentException()
      .isThrownBy { refConventions("{ref: server.port}") }
      .withMessageContaining("use `{ concept-ref: server.port }`")
  }

  @Test
  fun `a tag is referenced with ref, not concept-ref`() {
    assertThatIllegalArgumentException()
      .isThrownBy { refConventions("{concept-ref: http.method}") }
      .withMessageContaining("use `{ ref: http.method }`")
  }

  private fun refConventions(entry: String) = tagConventions(
    """
    concepts:
      - { concept: server.port, type: int, dd-name: server.port, otel-name: server.port }
    span_types:
      http.server: {span-kind: server, tags: [{dd-name: http.method}, $entry]}
    """
  )

  @Test
  fun `the assignment report lists each concept's names and kinds`() {
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(directory.conventionsFile(portConcepts), output)

    val report = contents(output).getValue("tag-assignment.txt").substringAfter("# CONCEPTS")
    assertThat(report.lines().map { it.trim().replace(Regex("\\s+"), " ") }).contains(
      "server.port datadog server.port otel server.port resolves on server",
      "peer.server.port datadog peer.port otel server.port resolves on client, producer, consumer",
      "client.port datadog peer.port otel client.port resolves on server",
    )
  }

  @Test
  fun `span-kind-neutral widens a typed rename to every direction`() {
    val conventions = tagConventions(
      """
      span_types:
        db.client:
          span-kind: client
          tags: [{dd-name: db.type, otel-name: db.system, span-kind-neutral: true}]
      """
    )

    val dbType = TagRegistry.build(conventions).tags.single()

    assertThat(dbType.otelName).isEqualTo("db.system")
    assertThat(dbType.scopedOtelNames).isEmpty()
  }

  @TableTest(
    """
          scenario                     | domain                                                                                                                                                               | message
          scoped rename to own name    | span_types: {a: {span-kind: client, tags: [{dd-name: x, otel-name: x}]}}                                                                                             | collides with canonical
          shared name renamed itself   | span_types: {a: {span-kind: server, tags: [{dd-name: x, otel-name: o}]}, b: {span-kind: client, tags: [{dd-name: y, otel-name: x}]}}                                  | is a Datadog name, and also another tag's OpenTelemetry name
          same name in one direction   | span_types: {a: {span-kind: server, tags: [{dd-name: x, otel-name: o}]}, b: {span-kind: consumer, tags: [{dd-name: y, otel-name: o}]}}                                | claimed by both 'x' and 'y' on inbound spans
          neutral contradicts scoped   | span_types: {a: {span-kind: server, tags: [{dd-name: x, otel-name: o, span-kind-neutral: true}]}, b: {span-kind: client, tags: [{dd-name: y, otel-name: o}]}}         | on outbound spans
          map-form otel-name           | span_types: {a: {span-kind: client, tags: [{dd-name: x, otel-name: {outbound: o}}]}}                                                                                  | invalid otel-name
          mixin of other direction     | '{span_types: {c: {span-kind: client, include: [m]}}, mixins: {m: {span-kind: server}}}'                                                                                  | receives mixin 'm', which is inbound
          mixin on undirected type     | '{span_types: {c: {include: [m]}}, mixins: {m: {span-kind: server}}}'                                                                                                     | (no span-kind) receives mixin 'm'
          child changes direction      | '{span_types: {p: {abstract: true, span-kind: client}, c: {extends: p, span-kind: server}}}'                                                                              | span type 'c' (inbound) changes the direction it inherits from 'p' (outbound)
          inherited ref other side     | '{span_types: {p: {span-kind: client, tags: [{ref: x}]}, c: {extends: p, span-kind: server, include: [i]}}, mixins: {o: {span-kind: client, tags: [{dd-name: x}]}, i: {span-kind: server, tags: [{dd-name: x}]}}}' | changes the direction it inherits from 'p'
          neutral per-direction tag    | mixins: {a: {span-kind: server, tags: [{dd-name: x, otel-name: o}]}, b: {span-kind: client, tags: [{dd-name: x, otel-name: p, span-kind-neutral: true}]}} | is declared once per direction
          repeated in one direction    | mixins: {a: {span-kind: server, tags: [{dd-name: x}]}, b: {span-kind: consumer, tags: [{dd-name: x}]}}                                                                | declared in both
          repeated without direction   | '{span_types: {base: {abstract: true, tags: [{dd-name: x}]}}, mixins: {m: {span-kind: server, tags: [{dd-name: x}]}}}'                                                     | declared in both
          ref without matching side    | '{span_types: {t: {span-kind: internal, tags: [{ref: x}]}}, mixins: {a: {span-kind: server, tags: [{dd-name: x}]}, b: {span-kind: client, tags: [{dd-name: x}]}}}'        | is declared once per direction
          """
  )
  fun `direction rules for OpenTelemetry names are enforced`(domain: String, message: String) {
    val yaml = directory.conventionsFile(domain)

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining(message)
  }

  @Test
  fun `a declared name containing @ is allowed when it collides with no derived identity`() {
    val conventions = tagConventions(
      """
      mixins:
        misc: {tags: [{dd-name: user@domain}]}
      """
    )

    assertThat(TagRegistry.build(conventions).tags.map { it.name }).containsExactly("user@domain")
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

  @Test
  fun `the tracer overlay declares routed keys and marks intercepted tags`() {
    val yaml = directory.conventionsFile(
      """
      span_types:
        base:
          abstract: true
          tags: [{dd-name: service, otel-name: service.name}, {dd-name: component}]
      """
    )
    val overlay = directory.writeFile(
      "overlay.yaml",
      """
      tags: [{dd-name: resource.name}]
      intercepted: [service]
      """
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output, overlay)

    val knownTags = contents(output).getValue("java/datadog/trace/api/KnownTags.java")
    assertThat(knownTags)
      .contains(
        "COMPONENT = 0x0001FFFF00000000L",
        "RESOURCE_NAME = 0x0002FFFF00000002L",
        "SERVICE = 0x0003FFFF00000002L",
        "public static final int SERVICE_SERIAL_NUM = 3;",
        "public static final int SERIAL_LIMIT = 4;"
      )
  }

  @Test
  fun `a tracer overlay mixin applies its tags to shared span types`() {
    val mapper = ObjectMapper(YAMLFactory())
    val type = object : TypeReference<Map<String, Any?>>() {}
    val root: Map<String, Any?> =
      mapper.readValue(
        """
        span_types:
          base: {abstract: true, tags: [{dd-name: service}]}
          http.server: {extends: base, span-kind: server, tags: [{dd-name: http.route}]}
          db.client: {extends: base, span-kind: client, tags: [{dd-name: db.type}]}
        """,
        type
      )
    val overlay: Map<String, Any?> =
      mapper.readValue(
        """
        mixins:
          servlet: {applies: [http.server], tags: [{dd-name: servlet.context}, {dd-name: servlet.path}]}
        intercepted: [servlet.context]
        """,
        type
      )

    val conv = TagConventions.parse(root, overlay)

    assertThat(conv.resolve("http.server").map { it.ddName })
      .containsExactlyInAnyOrder("service", "http.route", "servlet.context", "servlet.path")
    assertThat(conv.resolve("db.client").map { it.ddName }).containsExactlyInAnyOrder("service", "db.type")
    assertThat(conv.interceptedNames).contains("servlet.context")
  }

  @Test
  fun `a tracer overlay span type extends a shared one`() {
    val mapper = ObjectMapper(YAMLFactory())
    val type = object : TypeReference<Map<String, Any?>>() {}
    val root: Map<String, Any?> =
      mapper.readValue(
        """
        span_types:
          base: {abstract: true, tags: [{dd-name: service}]}
          http.server: {extends: base, span-kind: server, tags: [{dd-name: http.route}]}
        """,
        type
      )
    val overlay: Map<String, Any?> =
      mapper.readValue(
        """
        span_types:
          servlet.request: {extends: http.server, tags: [{dd-name: servlet.context}, {dd-name: servlet.path}]}
        intercepted: [servlet.context]
        """,
        type
      )

    val conv = TagConventions.parse(root, overlay)

    assertThat(conv.concreteTypes()).contains("http.server", "servlet.request")
    assertThat(conv.resolve("servlet.request").map { it.ddName })
      .containsExactlyInAnyOrder("service", "http.route", "servlet.context", "servlet.path")
    assertThat(conv.resolve("http.server").map { it.ddName }).containsExactlyInAnyOrder("service", "http.route")
  }

  @Test
  fun `ids are bare and names keep the full suffix even when the name already ends with it`() {
    val yaml = directory.conventionsFile(
      "span_types: {base: {tags: [{dd-name: cluster.id}, {dd-name: destination.name}]}}"
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    val knownTags = contents(output).getValue("java/datadog/trace/api/KnownTags.java")
    assertThat(knownTags)
      .contains(
        "long CLUSTER_ID = ",
        "String CLUSTER_ID_NAME = \"cluster.id\"",
        "long DESTINATION_NAME = ",
        "String DESTINATION_NAME_NAME = \"destination.name\""
      )
      .doesNotContain("CLUSTER_ID_ID", "DESTINATION_NAME_ID", "String DESTINATION_NAME = ")
  }

  @Test
  fun `names that generate the same constant are rejected with how to fix it`() {
    val yaml = directory.conventionsFile("span_types: {base: {tags: [{dd-name: aws.service}, {dd-name: aws_service}]}}")

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining("Tags 'aws.service' and 'aws_service' both generate the constant AWS_SERVICE_NAME")
      .withMessageContaining("tag-conventions-java.yaml")
      .withMessageContaining("java_names:\n    aws_service: <UPPER_CASE_BASE>")
  }

  @Test
  fun `a constant clash preserves previous output`() {
    val yaml = directory.conventionsFile("span_types: {base: {tags: [{dd-name: aws.service}]}}")
    val output = File(directory, "generated")
    TagRegistryGenerator.generate(yaml, output)
    val previous = contents(output)
    directory.conventionsFile("span_types: {base: {tags: [{dd-name: aws.service}, {dd-name: aws_service}]}}")

    assertThatIllegalArgumentException().isThrownBy { TagRegistryGenerator.generate(yaml, output) }
      .withMessageContaining("both generate the constant")

    assertThat(contents(output)).isEqualTo(previous)
  }

  @Test
  fun `java_names gives a tag its own constants`() {
    val yaml = directory.conventionsFile("span_types: {base: {tags: [{dd-name: aws.service}, {dd-name: aws_service}]}}")
    val overlay = directory.writeFile("overlay.yaml", "java_names: {aws_service: TOP_LEVEL_AWS_SERVICE}")
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output, overlay)

    assertThat(contents(output).getValue("java/datadog/trace/api/KnownTags.java"))
      .contains(
        "String AWS_SERVICE_NAME = \"aws.service\"",
        "String TOP_LEVEL_AWS_SERVICE_NAME = \"aws_service\"",
        "long TOP_LEVEL_AWS_SERVICE = ",
        "int TOP_LEVEL_AWS_SERVICE_SERIAL_NUM = "
      )
  }

  @TableTest(
    """
          scenario           | overlay                      | message
          undeclared name    | 'intercepted: [missing]'     | is not declared
          ref in overlay     | 'tags: [{ref: service}]'     | must be declarations, not refs
          unknown section    | 'trace_level: {}'            | may only declare
          duplicate of a tag | 'tags: [{dd-name: service}]' | declared in both
          mixin with no applies | 'mixins: {servlet: {tags: [{dd-name: servlet.path}]}}' | must say which span types
          mixin reusing a shared name | 'mixins: {peer: {applies: [base], tags: [{dd-name: x.y}]}}' | reuses the name
          type with no extends | 'span_types: {servlet.request: {tags: [{dd-name: servlet.path}]}}' | must extend a span type
          type reusing a shared name | 'span_types: {base: {extends: base}}' | reuses the name of a shared span type
          java name of no tag | 'java_names: {missing: MISSING}' | names no registered tag
          java name not a constant | 'java_names: {service: svc}' | not to an upper-case Java identifier
          """
  )
  fun `invalid tracer overlays are rejected`(overlay: String, message: String) {
    val yaml =
      directory.conventionsFile(
        "span_types: {base: {tags: [{dd-name: service}]}}\nmixins: {peer: {tags: [{dd-name: peer.service}]}}"
      )
    val overlayFile = directory.writeFile("overlay.yaml", overlay)

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated"), overlayFile) }
      .withMessageContaining(message)
  }

  @Test
  fun `tags on one span type get distinct slots and tags that never co-occur share one`() {
    val yaml = directory.conventionsFile(
      """
      trace_level:
        tags: [{dd-name: env, required: required}]
      span_types:
        base:
          abstract: true
          tags: [{dd-name: service, required: required}]
        http.server:
          extends: base
          span-kind: server
          tags: [{dd-name: http.route, required: required}]
        db.client:
          extends: base
          span-kind: client
          tags: [{dd-name: db.type, required: required}, {dd-name: db.user}]
      """
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    // service is on both span types, so it takes slot 0; db.type and db.user share db.client, so
    // they take distinct slots, and http.route -- never on a span type with either -- reuses one of
    // them; env is trace-level (its own TagMap) and reuses slot 0. Every level is slotted.
    assertThat(contents(output).getValue("java/datadog/trace/api/KnownTags.java"))
      .contains(
        "DB_TYPE = 0x0001000100000000L",
        "DB_USER = 0x0002000200000000L",
        "ENV = 0x0003000000000004L",
        "HTTP_ROUTE = 0x0004000100000000L",
        "SERVICE = 0x0005000000000000L",
        "public static final int SLOT_COUNT = 3;"
      )
  }

  @Test
  fun `optional tags share slots only when a span type overflows`() {
    // 60 required tags and 6 optional ones on one span type: 66 tags, over the 63 slots.
    val required = (0 until 60).joinToString("\n") { "        - {dd-name: t.req$it, required: required}" }
    val optional = (0 until 6).joinToString("\n") { "        - {dd-name: t.opt$it}" }
    val yaml = directory.conventionsFile("span_types:\n  test:\n    span-kind: internal\n    tags:\n$required\n$optional\n")
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    // The required tags take exact slots 0-59; the optional ones spread over the 3 left, 2 each.
    val knownTags = contents(output).getValue("java/datadog/trace/api/KnownTags.java")
    val slotOf = Regex("""long T_(\w+) = 0x[0-9A-F]{4}([0-9A-F]{4})""").findAll(knownTags)
      .associate { it.groupValues[1] to it.groupValues[2].toInt(16) }
    assertThat((0 until 60).map { slotOf.getValue("REQ$it") }.toSet()).isEqualTo((0 until 60).toSet())
    assertThat((0 until 6).map { slotOf.getValue("OPT$it") }.groupingBy { it }.eachCount())
      .isEqualTo(mapOf(60 to 2, 61 to 2, 62 to 2))
    assertThat(Regex("shares slot").findAll(knownTags).count()).isEqualTo(6)
    assertThat(knownTags).contains("public static final int SLOT_COUNT = 63;")
  }

  @Test
  fun `optional tags keep exact slots when the span type fits`() {
    val yaml = directory.conventionsFile(
      """
      span_types:
        test:
          span-kind: internal
          tags: [{dd-name: test.name, required: required}, {dd-name: test.is_retry}, {dd-name: test.is_new}]
      """
    )
    val output = File(directory, "generated")

    TagRegistryGenerator.generate(yaml, output)

    val knownTags = contents(output).getValue("java/datadog/trace/api/KnownTags.java")
    assertThat(knownTags).contains("public static final int SLOT_COUNT = 3;").doesNotContain("shares slot")
  }

  @Test
  fun `a span type whose non-optional tags need more than 63 slots is rejected`() {
    val tags = (0 until 64).joinToString("\n") { "        - {dd-name: t.req$it, required: recommended}" }
    val yaml = directory.conventionsFile("span_types:\n  test:\n    span-kind: internal\n    tags:\n$tags\n")

    assertThatIllegalArgumentException()
      .isThrownBy { TagRegistryGenerator.generate(yaml, File(directory, "generated")) }
      .withMessageContaining("coloring needs more than 63 slots")
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
