package datadog.trace.core.tagprocessor;

import static datadog.trace.bootstrap.instrumentation.api.Tags.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.TagMap;
import datadog.trace.bootstrap.instrumentation.api.AppendableSpanLinks;
import datadog.trace.core.DDSpanContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalTagsAdderTest {

  @Mock DDSpanContext spanContext;
  @Mock AppendableSpanLinks links;

  static Stream<Arguments> baseServiceArguments() {
    return Stream.of(
        // (scenario, spanServiceName, expected base.service tag or null)
        arguments("service differs -> base.service set", "anotherOne", "test"),
        arguments("service matches exactly -> no base.service", "test", null),
        arguments("service matches case-insensitively -> no base.service", "TeSt", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("baseServiceArguments")
  void addsBaseServiceWhenServiceDiffersFromDdService(
      String scenario, String spanServiceName, String expectedBaseService) {
    InternalTagsAdder adder = new InternalTagsAdder("test", null);
    when(spanContext.getServiceName()).thenReturn(spanServiceName);

    TagMap unsafeTags = TagMap.fromMap(Collections.emptyMap());
    adder.processTags(unsafeTags, spanContext, links);

    assertEquals(expectedBaseService, unsafeTags.getString(DDTags.BASE_SERVICE));
  }

  static Stream<Arguments> versionArguments() {
    return Stream.of(
        // (scenario, ddService, spanServiceName, ddVersion, existing tags, expected version tag)
        arguments("matches, no version configured", "same", "same", null, noTags(), null),
        arguments(
            "differs, version not added on base.service branch",
            "same",
            "different",
            "1.0",
            noTags(),
            null),
        arguments(
            "differs, span keeps its own version",
            "same",
            "different",
            "1.0",
            versionTag("2.0"),
            "2.0"),
        arguments(
            "matches, existing version not overwritten",
            "same",
            "same",
            null,
            versionTag("2.0"),
            "2.0"),
        arguments(
            "matches, configured version not overwriting existing",
            "same",
            "same",
            "1.0",
            versionTag("2.0"),
            "2.0"),
        arguments("matches, configured version added", "same", "same", "1.0", noTags(), "1.0"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("versionArguments")
  void addsVersionWhenServiceMatchesDdService(
      String scenario,
      String ddService,
      String spanServiceName,
      String ddVersion,
      Map<String, Object> existingTags,
      String expectedVersion) {
    InternalTagsAdder adder = new InternalTagsAdder(ddService, ddVersion);
    when(spanContext.getServiceName()).thenReturn(spanServiceName);

    TagMap unsafeTags = TagMap.fromMap(existingTags);
    adder.processTags(unsafeTags, spanContext, links);

    assertEquals(expectedVersion, unsafeTags.getString(VERSION));
  }

  /**
   * Regression for the empty-DD_SERVICE edge case (see PR #11555 review): an explicitly-empty
   * service name is a valid config (the config provider passes "" through, not the default). The
   * version branch must still be reached when the span's service name also matches the empty
   * configured service -- pre-building the base.service entry must not short-circuit it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("emptyServiceArguments")
  void emptyDdServicePreservesVersionHandling(
      String scenario, String spanServiceName, String expectedVersion) {
    InternalTagsAdder adder = new InternalTagsAdder("", "1.0");
    lenient().when(spanContext.getServiceName()).thenReturn(spanServiceName);

    TagMap unsafeTags = TagMap.fromMap(Collections.emptyMap());
    adder.processTags(unsafeTags, spanContext, links);

    assertEquals(expectedVersion, unsafeTags.getString(VERSION));
  }

  static Stream<Arguments> emptyServiceArguments() {
    return Stream.of(
        // empty span service matches empty DD_SERVICE -> version branch reached
        arguments("empty service matches -> version added", "", "1.0"),
        // non-empty span service differs from empty DD_SERVICE -> base.service branch, no version
        arguments("non-empty service differs -> no version", "nonempty", null));
  }

  private static Map<String, Object> noTags() {
    return Collections.emptyMap();
  }

  private static Map<String, Object> versionTag(String version) {
    Map<String, Object> tags = new HashMap<>();
    tags.put("version", version);
    return tags;
  }
}
