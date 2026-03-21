package datadog.trace.core.tagprocessor;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QueryObfuscatorTest {

  static Stream<Arguments> tagsProcessingArguments() {
    return Stream.of(
        Arguments.of(
            "key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2",
            "key1=val1&<redacted>&key2=val2"),
        Arguments.of("app_key=1111&application_key=2222", "<redacted>&<redacted>"),
        Arguments.of("email=foo@bar.com", "email=foo@bar.com"));
  }

  @ParameterizedTest
  @MethodSource("tagsProcessingArguments")
  void tagsProcessing(String query, String expectedQuery) {
    QueryObfuscator obfuscator = new QueryObfuscator(null);
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put(Tags.HTTP_URL, "http://site.com/index");
    tags.put(DDTags.HTTP_QUERY, query);

    Map<String, Object> result =
        obfuscator.processTags(
            tags,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(expectedQuery, result.get(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/index?" + expectedQuery, result.get(Tags.HTTP_URL));
  }

  static final String CUSTOM_REGEXP =
      "(?i)(?:(?:\"|%22)?)(?:(?:old[-_]?|new[-_]?)?p(?:ass)?w(?:or)?d(?:1|2)?|pass(?:[-_]?phrase)?|email|secret|(?:api[-_]?|private[-_]?|public[-_]?|access[-_]?|secret[-_]?|app(?:lication)?[-_]?)key(?:[-_]?id)?|token|consumer[-_]?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)(?:(?:\\s|%20)*(?:=|%3D)[^&]+|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22))|(?:bearer(?:\\s|%20)+[a-z0-9._\\-]+|token(?::|%3A)[a-z0-9]{13}|gh[opsu]_[0-9a-zA-Z]{36}|ey[I-L](?:[\\w=-]|%3D)+\\.ey[I-L](?:[\\w=-]|%3D)+(?:\\.(?:[\\w.+/=-]|%3D|%2F|%2B)+)?|-{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY-{5}[^\\-]+-{5}END(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY(?:-{5})?(?:\\n|%0A)?|(?:ssh-(?:rsa|dss)|ecdsa-[a-z0-9]+-[a-z0-9]+)(?:\\s|%20|%09)+(?:[a-z0-9/.+]|%2F|%5C|%2B){100,}(?:=|%3D)*(?:(?:\\s|%20|%09)+[a-z0-9._-]+)?)";

  static Stream<Arguments> tagsProcessingWithCustomRegexpForEmailArguments() {
    return Stream.of(
        Arguments.of(
            "key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2",
            "key1=val1&<redacted>&key2=val2"),
        Arguments.of("app_key=1111&application_key=2222", "<redacted>&<redacted>"),
        Arguments.of("email=foo@bar.com", "<redacted>"));
  }

  @ParameterizedTest
  @MethodSource("tagsProcessingWithCustomRegexpForEmailArguments")
  void tagsProcessingWithCustomRegexpForEmail(String query, String expectedQuery) {
    QueryObfuscator obfuscator = new QueryObfuscator(CUSTOM_REGEXP);
    Map<String, Object> tags = new LinkedHashMap<>();
    tags.put(Tags.HTTP_URL, "http://site.com/index");
    tags.put(DDTags.HTTP_QUERY, query);

    Map<String, Object> result =
        obfuscator.processTags(
            tags,
            null,
            Collections.<datadog.trace.bootstrap.instrumentation.api.AgentSpanLink>emptyList());

    assertEquals(expectedQuery, result.get(DDTags.HTTP_QUERY));
    assertEquals("http://site.com/index?" + expectedQuery, result.get(Tags.HTTP_URL));
  }
}
