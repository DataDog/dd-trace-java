package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.*;

import com.squareup.moshi.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.core.test.DDCoreSpecification;
import datadog.trace.payloadtags.PayloadTagsData;
import datadog.trace.payloadtags.PayloadTagsData.PathAndValue;
import datadog.trace.util.json.PathCursor;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class PayloadTagsProcessorTest extends DDCoreSpecification {

  static PathCursor pc() {
    return new PathCursor(10);
  }

  @Test
  void disabledByDefault() {
    assertNull(PayloadTagsProcessor.create(Config.get()));
  }

  @TableTest({
    "scenario                              | requestPayloadTagging | responsePayloadTagging | tagPrefix         | pathKey1     | pathKey2       | pathKey3 | pathIndexKey",
    "req all resp all req prefix           | all                   | all                    | aws.request.body  | phoneNumber  | null           | null     | -1          ",
    "req all resp dollar33baz req prefix   | all                   | $[33].baz              | aws.request.body  | AWSAccountId | null           | null     | -1          ",
    "req dotbar resp all resp prefix       | $.bar                 | all                    | aws.response.body | Endpoints    | foobar         | Token    | -1          ",
    "req dotfoobar resp dotdotbarstar both | $.foo.bar             | $..bar.*               | aws.request.body  | phoneNumber  | null           | null     | -1          ",
    "req null resp all resp prefix         | null                  | all                    | aws.response.body | phoneNumbers | null           | null     | 5           ",
    "req all resp null req prefix          | all                   | null                   | aws.request.body  | Attributes   | KmsMasterKeyId | null     | -1          "
  })
  @ParameterizedTest(name = "{0}")
  void enabledWithDefaultsWhenConfigured(
      String scenario,
      String requestPayloadTagging,
      String responsePayloadTagging,
      String tagPrefix,
      String pathKey1,
      String pathKey2,
      String pathKey3,
      int pathIndexKey) {
    if (!"null".equals(requestPayloadTagging)) {
      injectSysConfig("trace.cloud.request.payload.tagging", requestPayloadTagging);
    }
    if (!"null".equals(responsePayloadTagging)) {
      injectSysConfig("trace.cloud.response.payload.tagging", responsePayloadTagging);
    }

    PayloadTagsProcessor ptp = PayloadTagsProcessor.create(Config.get());

    assertNotNull(ptp);
    assertEquals(10, ptp.maxDepth);
    assertEquals(758, ptp.maxTags);

    PathCursor matchingPath = new PathCursor(10);
    if (!"null".equals(pathKey1)) {
      matchingPath.push(pathKey1);
    }
    if (!"null".equals(pathKey2)) {
      matchingPath.push(pathKey2);
    }
    if (!"null".equals(pathKey3)) {
      matchingPath.push(pathKey3);
    }
    if (pathIndexKey >= 0) {
      matchingPath.push(pathIndexKey);
    }

    assertNotNull(ptp.redactionRulesByTagPrefix.get(tagPrefix).findMatching(matchingPath));
    assertNull(
        ptp.redactionRulesByTagPrefix.get(tagPrefix).findMatching(pc().push("non-matching-path")));
  }

  @TableTest({
    "scenario                            | requestPayloadTagging | responsePayloadTagging | maxDepth | maxTags",
    "req all resp all depth10 tags10     | all                   | all                    | 10       | 10     ",
    "req dotbar resp null depth7 tags42  | $.bar                 | null                   | 7        | 42     ",
    "req all resp dotstar depth12 tags50 | all                   | $.*                    | 12       | 50     ",
    "req null resp all depth8 tags33     | null                  | all                    | 8        | 33     "
  })
  @ParameterizedTest(name = "{0}")
  void enabledWithCustomLimits(
      String scenario,
      String requestPayloadTagging,
      String responsePayloadTagging,
      int maxDepth,
      int maxTags) {
    if (!"null".equals(requestPayloadTagging)) {
      injectSysConfig("trace.cloud.request.payload.tagging", requestPayloadTagging);
    }
    if (!"null".equals(responsePayloadTagging)) {
      injectSysConfig("trace.cloud.response.payload.tagging", responsePayloadTagging);
    }
    injectSysConfig("trace.cloud.payload.tagging.max-depth", String.valueOf(maxDepth));
    injectSysConfig("trace.cloud.payload.tagging.max-tags", String.valueOf(maxTags));

    PayloadTagsProcessor ptp = PayloadTagsProcessor.create(Config.get());

    assertNotNull(ptp);
    assertEquals(maxDepth, ptp.maxDepth);
    assertEquals(maxTags, ptp.maxTags);
  }

  static PayloadTagsProcessor tagsProcessor(
      String tagPrefix, List<String> redactionRules, int maxDepth, int maxTags) {
    PayloadTagsProcessor.RedactionRules rules =
        new PayloadTagsProcessor.RedactionRules.Builder()
            .addRedactionJsonPaths(redactionRules)
            .build();
    Map<String, PayloadTagsProcessor.RedactionRules> map = new HashMap<>();
    map.put(tagPrefix, rules);
    return new PayloadTagsProcessor(map, maxDepth, maxTags);
  }

  static PathAndValue pv(PathCursor path, Object value) {
    return new PathAndValue(path.toPath(), value);
  }

  static PayloadTagsData payloadData(List<PathAndValue> pathAndValues) {
    return new PayloadTagsData(pathAndValues.toArray(new PathAndValue[0]));
  }

  static Map<String, Object> spanTags(String tagPrefix, List<PathAndValue> pathAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(tagPrefix, payloadData(pathAndValues));
    return map;
  }

  @Test
  void preserveAllSpanTagsExceptForPayloadData() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> spanTagsMap = new LinkedHashMap<>();
    spanTagsMap.put("foo", "bar");
    spanTagsMap.put("tag1", 1);
    spanTagsMap.put("payload", new PayloadTagsData(new PathAndValue[0]));

    Map<String, Object> result =
        ptp.processTags(
            spanTagsMap,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("bar", result.get("foo"));
    assertEquals(1, result.get("tag1"));
    assertFalse(result.containsKey("payload"));
    assertEquals(2, result.size());
  }

  @Test
  void expandPayloadToTags() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> spanTagsMap = new LinkedHashMap<>();
    spanTagsMap.put("foo", "bar");
    spanTagsMap.put("tag1", 1);
    spanTagsMap.put("payload", payloadData(Arrays.asList(pv(pc().push("tag1"), 0))));

    Map<String, Object> result =
        ptp.processTags(
            spanTagsMap,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("bar", result.get("foo"));
    assertEquals(1, result.get("tag1"));
    assertEquals(0, result.get("payload.tag1"));
  }

  @Test
  void expandPreservingTagTypes() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> st =
        spanTags(
            "payload",
            Arrays.asList(
                pv(pc().push("tag1"), 11),
                pv(pc().push("tag2").push("Value"), 2342L),
                pv(pc().push("tag3").push(0), 3.14d),
                pv(pc().push("tag4").push("Value").push(0), "string"),
                pv(pc().push("tag5"), null),
                pv(pc().push("tag6"), false)));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(11, result.get("payload.tag1"));
    assertEquals(2342L, result.get("payload.tag2.Value"));
    assertEquals(3.14d, result.get("payload.tag3.0"));
    assertEquals("string", result.get("payload.tag4.Value.0"));
    assertNull(result.get("payload.tag5"));
    assertTrue(result.containsKey("payload.tag5"));
    assertEquals(false, result.get("payload.tag6"));
  }

  @Test
  void expandUnknownTagValuesToString() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.<String>emptyList(), 10, 758);
    Instant unknownValue = Instant.now();
    Map<String, Object> st =
        spanTags("payload", Arrays.asList(pv(pc().push("tag7"), unknownValue)));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(unknownValue.toString(), result.get("payload.tag7"));
  }

  @Test
  void expandStringifiedJsonTags() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> st =
        spanTags(
            "p",
            Arrays.asList(
                pv(pc().push("j1"), "{}"),
                pv(pc().push("j2"), "[]"),
                pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
                pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}")));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("1", result.get("p.j3.0"));
    assertEquals(2, result.get("p.j3.1"));
    assertEquals(3.14d, result.get("p.j3.2"));
    assertNull(result.get("p.j3.3"));
    assertTrue(result.containsKey("p.j3.3"));
    assertEquals(true, result.get("p.j3.4"));
    assertEquals("bar", result.get("p.j4.foo"));
    assertEquals(42, result.get("p.j4.baz"));
  }

  @Test
  void expandSerializedEscapedInnerJsonWithinInnerJson() throws Exception {
    Buffer b0 = new Buffer();
    JsonWriter.of(b0)
        .beginObject()
        .name("a")
        .value(1.15)
        .name("password")
        .value("my-secret-password")
        .endObject()
        .close();

    Buffer b1 = new Buffer();
    JsonWriter.of(b1)
        .beginObject()
        .name("id")
        .value(45)
        .name("user")
        .value(b0.readUtf8())
        .endObject()
        .close();

    Buffer b2 = new Buffer();
    JsonWriter.of(b2)
        .beginObject()
        .name("a")
        .value(33)
        .name("Message")
        .value(b1.readUtf8())
        .name("b")
        .value(true)
        .endObject()
        .close();

    String json = b2.readUtf8();
    PayloadTagsProcessor ptp = tagsProcessor("dd", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> st = spanTags("dd", Arrays.asList(pv(pc(), json)));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(33, result.get("dd.a"));
    assertEquals(45, result.get("dd.Message.id"));
    assertEquals(1.15d, result.get("dd.Message.user.a"));
    assertEquals("my-secret-password", result.get("dd.Message.user.password"));
    assertEquals(true, result.get("dd.b"));
  }

  static Stream<Arguments> keepFailedToParseJsonAsIsArguments() {
    return Stream.of(
        Arguments.of("{'foo: 'bar'"),
        Arguments.of("[1, 2"),
        Arguments.of("[1, 2] "),
        Arguments.of(" [1, 2]"),
        Arguments.of("{'foo: 'bar'} "),
        Arguments.of(" {'foo: 'bar'}"));
  }

  @ParameterizedTest
  @MethodSource("keepFailedToParseJsonAsIsArguments")
  void keepFailedToParseJsonAsIs(String invalidJson) {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> st = spanTags("p", Arrays.asList(pv(pc().push("key"), invalidJson)));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(invalidJson, result.get("p.key"));
  }

  @Test
  void expandBinaryIfJson() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> st =
        spanTags(
            "p",
            Arrays.asList(
                pv(pc().push("j0"), new ByteArrayInputStream("{}".getBytes())),
                pv(pc().push("j1"), new ByteArrayInputStream("{'foo': 'bar'}".getBytes())),
                pv(pc().push("j2"), new ByteArrayInputStream("[1, true]".getBytes()))));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("bar", result.get("p.j1.foo"));
    assertEquals(1, result.get("p.j2.0"));
    assertEquals(true, result.get("p.j2.1"));
  }

  static Stream<Arguments> expandBinaryEscapedJsonTagsArguments() {
    return Stream.of(
        Arguments.of("{ \"a\": 1.15, \"password\": \"my-secret-password\" }"),
        Arguments.of("\"{ 'a': 1.15, 'password': 'my-secret-password' }\""),
        Arguments.of("\"{ \\\"a\\\": 1.15, \\\"password\\\": \\\"my-secret-password\\\" }\""),
        Arguments.of("'{ \"a\": 1.15, \"password\": \"my-secret-password\" }'"),
        Arguments.of("\"{ \\\"a\\\": 1.15, \\\"password\\\": \\\"my-secret-password\\\" }\""));
  }

  @ParameterizedTest
  @MethodSource("expandBinaryEscapedJsonTagsArguments")
  void expandBinaryEscapedJsonTags(String innerJson) {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.<String>emptyList(), 10, 758);
    String outerJson = "{ \"inner\": " + innerJson + "}";
    Map<String, Object> st =
        spanTags(
            "p", Arrays.asList(pv(pc().push("v"), new ByteArrayInputStream(outerJson.getBytes()))));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(1.15d, result.get("p.v.inner.a"));
    assertEquals("my-secret-password", result.get("p.v.inner.password"));
  }

  static Stream<Arguments> useBinaryValueIfNotJsonOrCouldntBeParsedArguments() {
    return Stream.of(
        Arguments.of(" [1]"), Arguments.of(" {'foo': 'bar'}"), Arguments.of("invalid:"));
  }

  @ParameterizedTest
  @MethodSource("useBinaryValueIfNotJsonOrCouldntBeParsedArguments")
  void useBinaryValueIfNotJsonOrCouldntBeParsed(String invalidJson) {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.<String>emptyList(), 10, 758);
    Map<String, Object> st =
        spanTags(
            "p",
            Arrays.asList(pv(pc().push("key"), new ByteArrayInputStream(invalidJson.getBytes()))));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("<binary>", result.get("p.key"));
  }

  @Test
  void applyRedactionRules() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Arrays.asList("$.j3[0]", "$.j4.baz"), 10, 758);
    Map<String, Object> st =
        spanTags(
            "p",
            Arrays.asList(
                pv(pc().push("j1"), "{}"),
                pv(pc().push("j2"), "[]"),
                pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
                pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}")));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("redacted", result.get("p.j3.0"));
    assertEquals(2, result.get("p.j3.1"));
    assertEquals(3.14d, result.get("p.j3.2"));
    assertNull(result.get("p.j3.3"));
    assertTrue(result.containsKey("p.j3.3"));
    assertEquals(true, result.get("p.j3.4"));
    assertEquals("bar", result.get("p.j4.foo"));
    assertEquals("redacted", result.get("p.j4.baz"));
  }

  @Test
  void respectMaxTagsLimit() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Arrays.asList("$.j3[0]", "$.j4.baz"), 10, 4);
    Map<String, Object> st =
        spanTags(
            "p",
            Arrays.asList(
                pv(pc().push("j1"), "{}"),
                pv(pc().push("j2"), "[]"),
                pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
                pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}")));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("redacted", result.get("p.j3.0"));
    assertEquals(2, result.get("p.j3.1"));
    assertEquals(3.14d, result.get("p.j3.2"));
    assertNull(result.get("p.j3.3"));
    assertTrue(result.containsKey("p.j3.3"));
    assertEquals(true, result.get("_dd.payload_tags_incomplete"));
  }

  @Test
  void respectMaxDepthLimit() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Arrays.asList("$.j3[0]", "$.j4.baz"), 3, 800);
    Map<String, Object> st =
        spanTags(
            "p",
            Arrays.asList(
                pv(pc().push("j3"), "['1', 2, 3.14, null, true, [ 1, [ 2, 3 ] ]]"),
                pv(
                    pc().push("j4"),
                    "{'foo': 'bar', 'baz': 42, 'nested': { 'a': 1, 'b': { 'c': 2 } } }")));

    Map<String, Object> result =
        ptp.processTags(
            st,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals("redacted", result.get("p.j3.0"));
    assertEquals(2, result.get("p.j3.1"));
    assertEquals(3.14d, result.get("p.j3.2"));
    assertNull(result.get("p.j3.3"));
    assertTrue(result.containsKey("p.j3.3"));
    assertEquals(true, result.get("p.j3.4"));
    assertEquals(1, result.get("p.j3.5.0"));
    assertEquals("bar", result.get("p.j4.foo"));
    assertEquals("redacted", result.get("p.j4.baz"));
    assertEquals(1, result.get("p.j4.nested.a"));
  }
}
