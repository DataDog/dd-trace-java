package datadog.trace.core.tagprocessor

import com.squareup.moshi.JsonWriter
import datadog.trace.payloadtags.PayloadTagsData
import datadog.trace.payloadtags.PayloadTagsData.PathAndValue
import datadog.json.PathCursor
import datadog.trace.test.util.DDSpecification
import datadog.trace.api.Config
import okio.Buffer
import java.time.Instant

class PayloadTagsProcessorTest extends DDSpecification {

  PathCursor pc() {
    new PathCursor(10)
  }

  def "disabled by default"() {
    expect:
    !PayloadTagsProcessor.create(Config.get())
  }

  def "enabled with defaults when configured req #requestPayloadTagging resp #responsePayloadTagging"() {
    setup:
    requestPayloadTagging && injectSysConfig("trace.cloud.request.payload.tagging", requestPayloadTagging)
    responsePayloadTagging && injectSysConfig("trace.cloud.response.payload.tagging", responsePayloadTagging)

    when:
    def ptp = PayloadTagsProcessor.create(Config.get())

    then:
    ptp != null
    ptp.maxDepth == 10
    ptp.maxTags == 758
    ptp.redactionRulesByTagPrefix.get(tagPrefix).findMatching(pathMatchingDefaultRules) != null
    ptp.redactionRulesByTagPrefix.get(tagPrefix).findMatching(pc().push("non-matching-path")) == null

    where:
    requestPayloadTagging | responsePayloadTagging | tagPrefix           | pathMatchingDefaultRules
    "all"                 | "all"                  | "aws.request.body"  | pc().push("phoneNumber")
    "all"                 | "\$[33].baz"           | "aws.request.body"  | pc().push("AWSAccountId")
    "\$.bar"              | "all"                  | "aws.response.body" | pc().push("Endpoints").push("foobar").push("Token")
    "\$.foo.bar"          | "\$..bar.*"            | "aws.request.body"  | pc().push("phoneNumber")
    null                  | "all"                  | "aws.response.body" | pc().push("phoneNumbers").push(5)
    "all"                 | null                   | "aws.request.body"  | pc().push("Attributes").push("KmsMasterKeyId")
  }

  def "enabled with custom limits"() {
    setup:
    requestPayloadTagging && injectSysConfig("trace.cloud.request.payload.tagging", requestPayloadTagging)
    responsePayloadTagging && injectSysConfig("trace.cloud.response.payload.tagging", responsePayloadTagging)
    injectSysConfig("trace.cloud.payload.tagging.max-depth", "$maxDepth")
    injectSysConfig("trace.cloud.payload.tagging.max-tags", "$maxTags")

    when:
    def ptp = PayloadTagsProcessor.create(Config.get())

    then:
    ptp.maxDepth == maxDepth
    ptp.maxTags == maxTags

    where:
    requestPayloadTagging | responsePayloadTagging | maxDepth | maxTags
    "all"                 | "all"                  | 10       | 10
    "\$.bar"              | null                   | 7        | 42
    "all"                 | "\$.*"                 | 12       | 50
    null                  | "all"                  | 8        | 33
  }

  static PayloadTagsProcessor tagsProcessor(String tagPrefix, List<String> redactionRules, int maxDepth, int maxTags) {
    def rules = new PayloadTagsProcessor.RedactionRules.Builder().addRedactionJsonPaths(redactionRules).build()
    new PayloadTagsProcessor([(tagPrefix): rules], maxDepth, maxTags)
  }

  def "preserve all span tags except for payloadData"() {
    setup:
    def ptp = tagsProcessor("payload", [], 10, 758)
    def spanTags = [
      "foo": "bar",
      "tag1": 1,
      "payload": new PayloadTagsData([] as PathAndValue[])
    ]

    expect:
    ptp.processTags(spanTags, null, []) == ["foo": "bar", "tag1": 1]
  }

  static PathAndValue pv(PathCursor path, Object value) {
    new PathAndValue(path.toPath(), value)
  }

  static PayloadTagsData payloadData(List<PathAndValue> pathAndValues) {
    new PayloadTagsData(pathAndValues as PathAndValue[])
  }

  static LinkedHashMap<String, Object> spanTags(String tagPrefix, List<PathAndValue> pathAndValues) {
    [(tagPrefix): payloadData(pathAndValues)]
  }

  def "expand payload to tags"() {
    setup:
    def ptp = tagsProcessor("payload", [], 10, 758)
    def spanTags = [
      "foo": "bar",
      "tag1": 1,
      "payload": payloadData([pv(pc().push("tag1"), 0)])
    ]

    expect:
    ptp.processTags(spanTags, null, []) == ["foo": "bar", "tag1": 1, "payload.tag1": 0]
  }

  def "expand preserving tag types"() {
    setup:
    def ptp = tagsProcessor("payload", [], 10, 758)

    def st = spanTags("payload", [
      pv(pc().push("tag1"), 11),
      pv(pc().push("tag2").push("Value"), 2342l),
      pv(pc().push("tag3").push(0), 3.14d),
      pv(pc().push("tag4").push("Value").push(0), "string"),
      pv(pc().push("tag5"), null),
      pv(pc().push("tag6"), false),
    ])

    expect:
    ptp.processTags(st, null, []) == [
      "payload.tag1": 11,
      "payload.tag2.Value": 2342l,
      "payload.tag3.0": 3.14d,
      "payload.tag4.Value.0": "string",
      "payload.tag5": null,
      "payload.tag6": false,
    ]
  }

  def "expand unknown tag values to string"() {
    setup:
    def ptp = tagsProcessor("payload", [], 10, 758)
    def unknownValue = Instant.now()

    def st = spanTags("payload", [pv(pc().push("tag7"), unknownValue),])

    expect:
    ptp.processTags(st, null, []) == [
      "payload.tag7": unknownValue.toString(),
    ]
  }

  def "expand stringified JSON tags"() {
    setup:
    def ptp = tagsProcessor("p", [], 10, 758)

    def st = spanTags("p", [
      pv(pc().push("j1"), "{}"),
      pv(pc().push("j2"), "[]"),
      pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
      pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}"),
    ])

    expect:
    ptp.processTags(st, null, []) == [
      "p.j3.0": "1",
      "p.j3.1": 2,
      "p.j3.2": 3.14d,
      "p.j3.3": null,
      "p.j3.4": true,
      "p.j4.foo": "bar",
      "p.j4.baz": 42,
    ]
  }

  def "expand serialized escaped inner json within inner json"() {
    Buffer b0 = new Buffer()
    JsonWriter.of(b0)
      .beginObject()
      .name("a").value(1.15)
      .name("password").value("my-secret-password")
      .endObject()
      .close()

    Buffer b1 = new Buffer()
    JsonWriter.of(b1)
      .beginObject()
      .name("id").value(45)
      .name("user").value(b0.readUtf8())
      .endObject()
      .close()

    Buffer b2 = new Buffer()
    JsonWriter.of(b2)
      .beginObject()
      .name("a").value(33)
      .name("Message").value(b1.readUtf8())
      .name("b").value(true)
      .endObject()
      .close()

    String json = b2.readUtf8()

    setup:
    def ptp = tagsProcessor("dd", [], 10, 758)

    def st = spanTags("dd", [pv(pc(), json),])

    expect:
    ptp.processTags(st, null, []) == [
      'dd.a'                    : 33,
      'dd.Message.id'           : 45,
      'dd.Message.user.a'       : 1.15d,
      'dd.Message.user.password': 'my-secret-password',
      'dd.b'                    : true
    ]
  }

  def "keep failed to parse JSON as-is"() {
    setup:
    def ptp = tagsProcessor("p", [], 10, 758)

    def st = spanTags("p", [pv(pc().push("key"), invalidJson),])

    expect:
    ptp.processTags(st, null, []) == [
      "p.key": invalidJson,
    ]

    where:
    invalidJson << [
      "{'foo: 'bar'",
      "[1, 2",
      "[1, 2] ",
      " [1, 2]",
      "{'foo: 'bar'} ",
      " {'foo: 'bar'}",
    ]
  }

  def "expand binary if JSON"() {
    setup:
    def ptp = tagsProcessor("p", [], 10, 758)

    def st = spanTags("p", [
      pv(pc().push("j0"), new ByteArrayInputStream("{}".bytes)),
      pv(pc().push("j1"), new ByteArrayInputStream("{'foo': 'bar'}".bytes)),
      pv(pc().push("j2"), new ByteArrayInputStream("[1, true]".bytes)),
    ])

    expect:
    ptp.processTags(st, null, []) == [
      "p.j1.foo": "bar",
      "p.j2.0": 1,
      "p.j2.1": true,
    ]
  }

  def "expand binary escaped JSON tags"() {
    setup:
    def ptp = tagsProcessor("p", [], 10, 758)

    when:
    def st = spanTags("p", [pv(pc().push("v"), new ByteArrayInputStream("""{ "inner": $innerJson}""".bytes))])

    then:
    ptp.processTags(st, null, []) == [
      "p.v.inner.a": 1.15d,
      "p.v.inner.password": "my-secret-password",
    ]

    where:
    innerJson << [
      "{ \"a\": 1.15, \"password\": \"my-secret-password\" }",
      "\"{ 'a': 1.15, 'password': 'my-secret-password' }\"",
      '"{ \\"a\\": 1.15, \\"password\\": \\"my-secret-password\\" }"',
      "'{ \"a\": 1.15, \"password\": \"my-secret-password\" }'",
      '''"{ \\"a\\": 1.15, \\"password\\": \\"my-secret-password\\" }"'''
    ]
  }

  def "use <binary> value if not JSON or couldn't be parsed"() {
    setup:
    def ptp = tagsProcessor("p", [], 10, 758)

    def st = spanTags("p", [pv(pc().push("key"), new ByteArrayInputStream(invalidJson.bytes)),])

    expect:
    ptp.processTags(st, null, []) == [
      "p.key": "<binary>",
    ]

    where:
    invalidJson << [" [1]", " {'foo': 'bar'}", "invalid:"]
  }

  def "apply redaction rules"() {
    setup:
    def ptp = tagsProcessor("p", ["\$.j3[0]", "\$.j4.baz"], 10, 758)

    def st = spanTags("p", [
      pv(pc().push("j1"), "{}"),
      pv(pc().push("j2"), "[]"),
      pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
      pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}"),
    ])

    expect:
    ptp.processTags(st, null, []) == [
      "p.j3.0": "redacted",
      "p.j3.1": 2,
      "p.j3.2": 3.14d,
      "p.j3.3": null,
      "p.j3.4": true,
      "p.j4.foo": "bar",
      "p.j4.baz": "redacted",
    ]
  }

  def "respect max tags limit"() {
    setup:
    def ptp = tagsProcessor("p", ["\$.j3[0]", "\$.j4.baz"], 10, 4)

    def st = spanTags("p", [
      pv(pc().push("j1"), "{}"),
      pv(pc().push("j2"), "[]"),
      pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
      pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}"),
    ])

    expect:
    ptp.processTags(st, null, []) == [
      "p.j3.0": "redacted",
      "p.j3.1": 2,
      "p.j3.2": 3.14d,
      "p.j3.3": null,
      "_dd.payload_tags_incomplete": true
    ]
  }

  def "respect max depth limit"() {
    setup:
    def ptp = tagsProcessor("p", ["\$.j3[0]", "\$.j4.baz"], 3, 800)

    def st = spanTags("p", [
      pv(pc().push("j3"), "['1', 2, 3.14, null, true, [ 1, [ 2, 3 ] ]]"),
      pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42, 'nested': { 'a': 1, 'b': { 'c': 2 } } }"),
    ])

    expect:
    ptp.processTags(st, null, []) == [
      "p.j3.0": "redacted",
      "p.j3.1": 2,
      "p.j3.2": 3.14d,
      "p.j3.3": null,
      "p.j3.4": true,
      "p.j3.5.0": 1,
      "p.j4.foo": "bar",
      "p.j4.baz": "redacted",
      "p.j4.nested.a": 1,
    ]
  }
}

