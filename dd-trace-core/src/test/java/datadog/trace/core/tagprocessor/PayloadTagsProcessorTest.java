package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.squareup.moshi.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.api.TagMap;
import datadog.trace.junit.utils.config.WithConfigExtension;
import datadog.trace.payloadtags.PayloadTagsData;
import datadog.trace.payloadtags.PayloadTagsData.PathAndValue;
import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.json.PathCursor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class PayloadTagsProcessorTest extends DDJavaSpecification {

  private static PathCursor pc() {
    return new PathCursor(10);
  }

  private static PathAndValue pv(PathCursor path, Object value) {
    return new PathAndValue(path.toPath(), value);
  }

  private static PayloadTagsData payloadData(PathAndValue... pvs) {
    return new PayloadTagsData(pvs);
  }

  private static Map<String, Object> spanTags(String tagPrefix, PathAndValue... pvs) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(tagPrefix, payloadData(pvs));
    return map;
  }

  private static PayloadTagsProcessor tagsProcessor(
      String tagPrefix, List<String> redactionRules, int maxDepth, int maxTags) {
    PayloadTagsProcessor.RedactionRules rules =
        new PayloadTagsProcessor.RedactionRules.Builder()
            .addRedactionJsonPaths(redactionRules)
            .build();
    Map<String, PayloadTagsProcessor.RedactionRules> rulesMap = new HashMap<>();
    rulesMap.put(tagPrefix, rules);
    return new PayloadTagsProcessor(rulesMap, maxDepth, maxTags);
  }

  /** Builds a LinkedHashMap from alternating key-value pairs; supports null values. */
  @SafeVarargs
  private static <V> Map<String, V> mapOf(Object... pairs) {
    Map<String, V> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      @SuppressWarnings("unchecked")
      V val = (V) pairs[i + 1];
      result.put((String) pairs[i], val);
    }
    return result;
  }

  @Test
  void disabledByDefault() {
    assertNull(PayloadTagsProcessor.create(Config.get()));
  }

  static Stream<Arguments> enabledWithDefaultsWhenConfiguredArguments() {
    return Stream.of(
        Arguments.arguments("all", "all", "aws.request.body", pc().push("phoneNumber")),
        Arguments.arguments("all", "$[33].baz", "aws.request.body", pc().push("AWSAccountId")),
        Arguments.arguments(
            "$.bar",
            "all",
            "aws.response.body",
            pc().push("Endpoints").push("foobar").push("Token")),
        Arguments.arguments("$.foo.bar", "$..bar.*", "aws.request.body", pc().push("phoneNumber")),
        Arguments.arguments(null, "all", "aws.response.body", pc().push("phoneNumbers").push(5)),
        Arguments.arguments(
            "all", null, "aws.request.body", pc().push("Attributes").push("KmsMasterKeyId")));
  }

  @ParameterizedTest(name = "enabled with defaults when configured req {0} resp {1}")
  @MethodSource("enabledWithDefaultsWhenConfiguredArguments")
  void enabledWithDefaultsWhenConfigured(
      String requestPayloadTagging,
      String responsePayloadTagging,
      String tagPrefix,
      PathCursor pathMatchingDefaultRules) {
    if (requestPayloadTagging != null) {
      WithConfigExtension.injectSysConfig(
          "trace.cloud.request.payload.tagging", requestPayloadTagging);
    }
    if (responsePayloadTagging != null) {
      WithConfigExtension.injectSysConfig(
          "trace.cloud.response.payload.tagging", responsePayloadTagging);
    }

    PayloadTagsProcessor ptp = PayloadTagsProcessor.create(Config.get());

    assertNotNull(ptp);
    assertEquals(10, ptp.maxDepth);
    assertEquals(758, ptp.maxTags);
    assertNotNull(
        ptp.redactionRulesByTagPrefix.get(tagPrefix).findMatching(pathMatchingDefaultRules));
    assertNull(
        ptp.redactionRulesByTagPrefix.get(tagPrefix).findMatching(pc().push("non-matching-path")));
  }

  @TableTest({
    "scenario               | requestPayloadTagging | responsePayloadTagging | maxDepth | maxTags",
    "both req and resp all  | all                   | all                    | 10       | 10     ",
    "req filter, no resp    | $.bar                 |                        | 7        | 42     ",
    "req all, resp wildcard | all                   | $.*                    | 12       | 50     ",
    "no req, resp all       |                       | all                    | 8        | 33     "
  })
  void enabledWithCustomLimits(
      String requestPayloadTagging, String responsePayloadTagging, int maxDepth, int maxTags) {
    if (requestPayloadTagging != null) {
      WithConfigExtension.injectSysConfig(
          "trace.cloud.request.payload.tagging", requestPayloadTagging);
    }
    if (responsePayloadTagging != null) {
      WithConfigExtension.injectSysConfig(
          "trace.cloud.response.payload.tagging", responsePayloadTagging);
    }
    WithConfigExtension.injectSysConfig(
        "trace.cloud.payload.tagging.max-depth", String.valueOf(maxDepth));
    WithConfigExtension.injectSysConfig(
        "trace.cloud.payload.tagging.max-tags", String.valueOf(maxTags));

    PayloadTagsProcessor ptp = PayloadTagsProcessor.create(Config.get());

    assertEquals(maxDepth, ptp.maxDepth);
    assertEquals(maxTags, ptp.maxTags);
  }

  @Test
  void preserveAllSpanTagsExceptForPayloadData() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.emptyList(), 10, 758);
    Map<String, Object> spanTags = new LinkedHashMap<>();
    spanTags.put("foo", "bar");
    spanTags.put("tag1", 1);
    spanTags.put("payload", new PayloadTagsData(new PathAndValue[0]));

    TagMap unsafeTags = TagMap.fromMap(spanTags);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(mapOf("foo", "bar", "tag1", 1), unsafeTags);
  }

  @Test
  void expandPayloadToTags() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.emptyList(), 10, 758);
    Map<String, Object> spanTags = new LinkedHashMap<>();
    spanTags.put("foo", "bar");
    spanTags.put("tag1", 1);
    spanTags.put("payload", payloadData(pv(pc().push("tag1"), 0)));

    TagMap unsafeTags = TagMap.fromMap(spanTags);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(mapOf("foo", "bar", "tag1", 1, "payload.tag1", 0), unsafeTags);
  }

  @Test
  void expandPreservingTagTypes() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.emptyList(), 10, 758);

    Map<String, Object> st =
        spanTags(
            "payload",
            pv(pc().push("tag1"), 11),
            pv(pc().push("tag2").push("Value"), 2342L),
            pv(pc().push("tag3").push(0), 3.14d),
            pv(pc().push("tag4").push("Value").push(0), "string"),
            pv(pc().push("tag5"), null),
            pv(pc().push("tag6"), false));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    Map<String, Object> expected =
        mapOf(
            "payload.tag1",
            11,
            "payload.tag2.Value",
            2342L,
            "payload.tag3.0",
            3.14d,
            "payload.tag4.Value.0",
            "string",
            "payload.tag5",
            null,
            "payload.tag6",
            false);
    assertEquals(expected, unsafeTags);
  }

  @Test
  void expandUnknownTagValuesToString() {
    PayloadTagsProcessor ptp = tagsProcessor("payload", Collections.emptyList(), 10, 758);
    Instant unknownValue = Instant.now();

    Map<String, Object> st = spanTags("payload", pv(pc().push("tag7"), unknownValue));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(mapOf("payload.tag7", unknownValue.toString()), unsafeTags);
  }

  @Test
  void expandStringifiedJsonTags() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.emptyList(), 10, 758);

    Map<String, Object> st =
        spanTags(
            "p",
            pv(pc().push("j1"), "{}"),
            pv(pc().push("j2"), "[]"),
            pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
            pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}"));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    Map<String, Object> expected =
        mapOf(
            "p.j3.0",
            "1",
            "p.j3.1",
            2,
            "p.j3.2",
            3.14d,
            "p.j3.3",
            null,
            "p.j3.4",
            true,
            "p.j4.foo",
            "bar",
            "p.j4.baz",
            42);
    assertEquals(expected, unsafeTags);
  }

  @Test
  void expandSerializedEscapedInnerJsonWithinInnerJson() throws IOException {
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

    PayloadTagsProcessor ptp = tagsProcessor("dd", Collections.emptyList(), 10, 758);
    Map<String, Object> st = spanTags("dd", pv(pc(), json));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(
        mapOf(
            "dd.a",
            33,
            "dd.Message.id",
            45,
            "dd.Message.user.a",
            1.15d,
            "dd.Message.user.password",
            "my-secret-password",
            "dd.b",
            true),
        unsafeTags);
  }

  @ValueSource(
      strings = {"{'foo: 'bar'", "[1, 2", "[1, 2] ", " [1, 2]", "{'foo: 'bar'} ", " {'foo: 'bar'}"})
  @ParameterizedTest
  void keepFailedToParseJsonAsIs(String invalidJson) {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.emptyList(), 10, 758);
    Map<String, Object> st = spanTags("p", pv(pc().push("key"), invalidJson));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(mapOf("p.key", invalidJson), unsafeTags);
  }

  @Test
  void expandBinaryIfJson() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.emptyList(), 10, 758);

    Map<String, Object> st =
        spanTags(
            "p",
            pv(pc().push("j0"), new ByteArrayInputStream("{}".getBytes())),
            pv(pc().push("j1"), new ByteArrayInputStream("{'foo': 'bar'}".getBytes())),
            pv(pc().push("j2"), new ByteArrayInputStream("[1, true]".getBytes())));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(mapOf("p.j1.foo", "bar", "p.j2.0", 1, "p.j2.1", true), unsafeTags);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // standard JSON
        "{ \"a\": 1.15, \"password\": \"my-secret-password\" }",
        // JSON wrapped in double quotes (string-quoted)
        "\"{ 'a': 1.15, 'password': 'my-secret-password' }\"",
        // JSON with escaped quotes, wrapped in double quotes
        "\"{ \\\"a\\\": 1.15, \\\"password\\\": \\\"my-secret-password\\\" }\"",
        // JSON wrapped in single quotes
        "'{ \"a\": 1.15, \"password\": \"my-secret-password\" }'",
        // same as case 3, alternate source representation
        "\"{ \\\"a\\\": 1.15, \\\"password\\\": \\\"my-secret-password\\\" }\""
      })
  void expandBinaryEscapedJsonTags(String innerJson) {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.emptyList(), 10, 758);
    Map<String, Object> st =
        spanTags(
            "p",
            pv(
                pc().push("v"),
                new ByteArrayInputStream(("{ \"inner\": " + innerJson + "}").getBytes())));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(
        mapOf("p.v.inner.a", 1.15d, "p.v.inner.password", "my-secret-password"), unsafeTags);
  }

  @ValueSource(strings = {" [1]", " {'foo': 'bar'}", "invalid:"})
  @ParameterizedTest
  void useBinaryValueIfNotJsonOrCouldntBeParsed(String invalidJson) {
    PayloadTagsProcessor ptp = tagsProcessor("p", Collections.emptyList(), 10, 758);
    Map<String, Object> st =
        spanTags("p", pv(pc().push("key"), new ByteArrayInputStream(invalidJson.getBytes())));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(mapOf("p.key", "<binary>"), unsafeTags);
  }

  @Test
  void applyRedactionRules() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Arrays.asList("$.j3[0]", "$.j4.baz"), 10, 758);

    Map<String, Object> st =
        spanTags(
            "p",
            pv(pc().push("j1"), "{}"),
            pv(pc().push("j2"), "[]"),
            pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
            pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}"));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    Map<String, Object> expected =
        mapOf(
            "p.j3.0",
            "redacted",
            "p.j3.1",
            2,
            "p.j3.2",
            3.14d,
            "p.j3.3",
            null,
            "p.j3.4",
            true,
            "p.j4.foo",
            "bar",
            "p.j4.baz",
            "redacted");
    assertEquals(expected, unsafeTags);
  }

  @Test
  void respectMaxTagsLimit() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Arrays.asList("$.j3[0]", "$.j4.baz"), 10, 4);

    Map<String, Object> st =
        spanTags(
            "p",
            pv(pc().push("j1"), "{}"),
            pv(pc().push("j2"), "[]"),
            pv(pc().push("j3"), "['1', 2, 3.14, null, true]"),
            pv(pc().push("j4"), "{'foo': 'bar', 'baz': 42}"));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(
        mapOf(
            "p.j3.0",
            "redacted",
            "p.j3.1",
            2,
            "p.j3.2",
            3.14d,
            "p.j3.3",
            null,
            "_dd.payload_tags_incomplete",
            true),
        unsafeTags);
  }

  @Test
  void respectMaxDepthLimit() {
    PayloadTagsProcessor ptp = tagsProcessor("p", Arrays.asList("$.j3[0]", "$.j4.baz"), 3, 800);

    Map<String, Object> st =
        spanTags(
            "p",
            pv(pc().push("j3"), "['1', 2, 3.14, null, true, [ 1, [ 2, 3 ] ]]"),
            pv(
                pc().push("j4"),
                "{'foo': 'bar', 'baz': 42, 'nested': { 'a': 1, 'b': { 'c': 2 } } }"));

    TagMap unsafeTags = TagMap.fromMap(st);
    ptp.processTags(unsafeTags, null, link -> {});

    assertEquals(
        mapOf(
            "p.j3.0",
            "redacted",
            "p.j3.1",
            2,
            "p.j3.2",
            3.14d,
            "p.j3.3",
            null,
            "p.j3.4",
            true,
            "p.j3.5.0",
            1,
            "p.j4.foo",
            "bar",
            "p.j4.baz",
            "redacted",
            "p.j4.nested.a",
            1),
        unsafeTags);
  }
}
