package com.datadog.openfeature.internal.delivery;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.delivery.FlagSelectionTestSupport.RecordingHealthMetrics;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import java.net.URI;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.Level;
import org.tabletest.junit.TableTest;

class FlagSelectionRequestTest {
  private static final URI ENDPOINT = URI.create("https://cdn.example/ufc");

  @BeforeEach
  void captureLogs() {
    FlagSelectionTestSupport.resetReported();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
  }

  @AfterEach
  void resetLogs() {
    CapturingServiceProvider.reset();
    FlagSelectionTestSupport.resetReported();
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003,
        0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F,
        0x3000
      })
  void trimsEveryUnicodeWhiteSpaceCharacter(final int whiteSpace) {
    final String padding = String.valueOf((char) whiteSpace);

    final FlagSelectionRequest selection =
        FlagSelectionRequest.of(singletonList(padding + "a b" + padding + padding), null);

    assertEquals(singletonList("a b"), selection.tags());
  }

  @ParameterizedTest
  @ValueSource(ints = {0x1C, 0x1D, 0x1E, 0x1F, 0x180E, 0x200B, 0x2060, 0xFEFF})
  void keepsCharactersOutsideUnicodeWhiteSpace(final int character) {
    final String value = (char) character + "a" + (char) character;

    final FlagSelectionRequest selection = FlagSelectionRequest.of(null, singletonList(value));

    assertEquals(singletonList(value), selection.keyPatterns());
  }

  @Test
  void sortsByUtf8BytesRatherThanUtf16CodeUnits() {
    final FlagSelectionRequest selection =
        FlagSelectionRequest.of(asList("\uD83D\uDE00", "\uFF21", "a", "Z", "\u00E9"), null);

    assertEquals(asList("Z", "a", "\u00E9", "\uFF21", "\uD83D\uDE00"), selection.tags());
  }

  @Test
  void dropsEmptyValuesAndDuplicatesInEachList() {
    final FlagSelectionRequest selection =
        FlagSelectionRequest.of(asList(" b", "a", "", "b ", "\u3000"), asList("a", " a "));

    assertEquals(asList("a", "b"), selection.tags());
    assertEquals(singletonList("a"), selection.keyPatterns());
  }

  @Test
  void noListsAndEmptyListsSelectAllTheFlags() {
    assertTrue(FlagSelectionRequest.of(null, null).isNone());
    assertTrue(FlagSelectionRequest.of(emptyList(), emptyList()).isNone());
    assertTrue(FlagSelectionRequest.of(asList(" ", ""), singletonList("\t")).isNone());
    assertTrue(FlagSelectionRequest.NONE.isNone());
    assertEquals(FlagSelectionRequest.NONE, FlagSelectionRequest.of(emptyList(), null));
    assertNull(FlagSelectionRequest.NONE.dropReason());
    assertFalse(FlagSelectionRequest.of(singletonList(null), null).isNone());
    assertFalse(FlagSelectionRequest.urlTooLong().isNone());
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void malformedElementsAreReportedAsMalformedSelection() {
    final List raw = new ArrayList();
    raw.add("a");
    raw.add(42);
    final List<List<?>> malformed =
        asList(
            asList("a", null),
            raw,
            singletonList("\uD83D"),
            singletonList("a\uDE00"),
            singletonList("\uDE00\uD83D"),
            singletonList("\uD83Da"));

    for (final List<?> values : malformed) {
      assertEquals(
          "malformed_selection", FlagSelectionRequest.of(values, null).dropReason(), "tags");
      assertEquals(
          "malformed_selection", FlagSelectionRequest.of(null, values).dropReason(), "keyPatterns");
    }
  }

  @Test
  void aListThatFailsToBeReadIsMalformed() {
    final List<String> failing =
        new AbstractList<String>() {
          @Override
          public String get(final int index) {
            throw new IllegalStateException("concurrently modified");
          }

          @Override
          public int size() {
            return 1;
          }
        };

    assertEquals(
        "malformed_selection",
        assertDoesNotThrow(() -> FlagSelectionRequest.of(failing, null)).dropReason());
  }

  @Test
  void aListThatBreaksTheToArrayContractIsMalformed() {
    final List<String> broken =
        new AbstractList<String>() {
          @Override
          public String get(final int index) {
            return "area:checkout";
          }

          @Override
          public int size() {
            return 1;
          }

          @Override
          public Object[] toArray() {
            return null;
          }
        };

    assertEquals(
        "malformed_selection",
        assertDoesNotThrow(() -> FlagSelectionRequest.of(broken, null)).dropReason());
  }

  @TableTest({
    "scenario                                   | tags | tagLength | keyPatterns | keyPatternLength | reason             ",
    "31 values in each list                     | 31   | 1         | 31          | 1                |                    ",
    "32 tags                                    | 32   | 1         | 0           | 1                | too_many_values    ",
    "32 key patterns                            | 0    | 1         | 32          | 1                | too_many_values    ",
    "200-byte values                            | 1    | 200       | 1           | 200              |                    ",
    "201-byte key pattern                       | 1    | 1         | 1           | 201              | invalid_key_pattern",
    "201-byte tag                               | 1    | 201       | 1           | 1                | invalid_tag        ",
    "too_many_values before invalid_key_pattern | 1    | 1         | 32          | 201              | too_many_values    ",
    "invalid_key_pattern before invalid_tag     | 1    | 201       | 1           | 201              | invalid_key_pattern"
  })
  void validatesTheLimitsInPrecedenceOrder(
      final int tags,
      final int tagLength,
      final int keyPatterns,
      final int keyPatternLength,
      final String reason) {
    final FlagSelectionRequest selection =
        FlagSelectionRequest.of(values(tags, tagLength), values(keyPatterns, keyPatternLength));

    assertEquals(reason, selection.dropReason());
  }

  @Test
  void malformedSelectionTakesPrecedenceOverTheLimits() {
    final List<String> tooMany = values(32, 201);

    assertEquals(
        "malformed_selection", FlagSelectionRequest.of(tooMany, asList("a", null)).dropReason());
    assertEquals(
        "malformed_selection",
        FlagSelectionRequest.of(singletonList("\uD83D"), tooMany).dropReason());
  }

  @Test
  void comparesSelectionsInCanonicalForm() {
    final FlagSelectionRequest selection = FlagSelectionRequest.of(asList("b", "a"), null);

    assertEquals(selection, FlagSelectionRequest.of(asList(" a", "b", "a"), emptyList()));
    assertEquals(selection.hashCode(), FlagSelectionRequest.of(asList("a", "b"), null).hashCode());
    assertNotEquals(selection, FlagSelectionRequest.of(null, asList("a", "b")));
    assertNotEquals(selection, FlagSelectionRequest.NONE);
    assertEquals(
        FlagSelectionRequest.of(values(32, 1), null), FlagSelectionRequest.of(values(33, 1), null));
  }

  @TableTest({
    "scenario        | tag                   | keyPattern | query                                                                             ",
    "space and colon | 'release:summer sale' | 'ops-*'    | 'selected_flag_tags=release%3Asummer%20sale&selected_flag_key_patterns=ops-%2A'   ",
    "unreserved      | 'aZ09-._~'            | 'x'        | 'selected_flag_tags=aZ09-._~&selected_flag_key_patterns=x'                        ",
    "reserved        | '+,;=&#%/?@'          | ':'        | 'selected_flag_tags=%2B%2C%3B%3D%26%23%25%2F%3F%40&selected_flag_key_patterns=%3A'",
    "non-ASCII       | 'caf\u00e9'           | 'x'        | 'selected_flag_tags=caf%C3%A9&selected_flag_key_patterns=x'                       "
  })
  void percentEncodesValuesWithUppercaseHex(
      final String tag, final String keyPattern, final String query) {
    final URI sent =
        FlagSelectionRequest.of(singletonList(tag), singletonList(keyPattern)).appendTo(ENDPOINT);

    assertEquals("https://cdn.example/ufc?" + query, sent.toString());
  }

  @Test
  void encodesTheCharactersThatEncodeUriComponentKeeps() {
    final URI sent = FlagSelectionRequest.of(singletonList("!'()*"), null).appendTo(ENDPOINT);

    assertEquals("https://cdn.example/ufc?selected_flag_tags=%21%27%28%29%2A", sent.toString());
  }

  @Test
  void encodesCharactersOutsideTheBasicMultilingualPlane() {
    final URI sent =
        FlagSelectionRequest.of(singletonList("\uD83D\uDE00"), null).appendTo(ENDPOINT);

    assertEquals("https://cdn.example/ufc?selected_flag_tags=%F0%9F%98%80", sent.toString());
  }

  @TableTest({
    "scenario           | endpoint                                   | sent                                                                                                             ",
    "no query           | 'https://cdn.example/ufc'                  | 'https://cdn.example/ufc?selected_flag_tags=a&selected_flag_tags=b&selected_flag_key_patterns=c'                 ",
    "empty query        | 'https://cdn.example/ufc?'                 | 'https://cdn.example/ufc?selected_flag_tags=a&selected_flag_tags=b&selected_flag_key_patterns=c'                 ",
    "existing query     | 'https://cdn.example/ufc?dd_env=a+b&t=%2B' | 'https://cdn.example/ufc?dd_env=a+b&t=%2B&selected_flag_tags=a&selected_flag_tags=b&selected_flag_key_patterns=c'",
    "fragment           | 'https://cdn.example/ufc?dd_env=test#top'  | 'https://cdn.example/ufc?dd_env=test&selected_flag_tags=a&selected_flag_tags=b&selected_flag_key_patterns=c#top' ",
    "fragment, no query | 'http://user@localhost:8080/ufc.json#top'  | 'http://user@localhost:8080/ufc.json?selected_flag_tags=a&selected_flag_tags=b&selected_flag_key_patterns=c#top' ",
    "encoded path       | 'https://cdn.example/a%20b/ufc'            | 'https://cdn.example/a%20b/ufc?selected_flag_tags=a&selected_flag_tags=b&selected_flag_key_patterns=c'           "
  })
  void mergesTheParametersIntoTheEndpointQuery(final String endpoint, final String sent) {
    final FlagSelectionRequest selection =
        FlagSelectionRequest.of(asList("b", "a"), singletonList("c"));

    assertEquals(sent, selection.appendTo(URI.create(endpoint)).toString());
  }

  @Test
  void leavesTheEndpointUnchangedWithoutValues() {
    assertSame(ENDPOINT, FlagSelectionRequest.NONE.appendTo(ENDPOINT));
    assertSame(ENDPOINT, FlagSelectionRequest.of(values(32, 1), null).appendTo(ENDPOINT));
  }

  @Test
  void limitsTheWholeRequestUrlTo8192Bytes() {
    final FlagSelectionRequest selection = FlagSelectionRequest.of(singletonList("a"), null);
    final String base = "https://cdn.example/ufc?pad=";
    final int parameterLength = "&selected_flag_tags=a".length();

    final URI atLimit = URI.create(base + repeat('x', 8192 - base.length() - parameterLength));
    final URI overLimit = URI.create(base + repeat('x', 8193 - base.length() - parameterLength));

    assertEquals(8192, selection.appendTo(atLimit).toASCIIString().length());
    assertNull(selection.appendTo(overLimit));
  }

  @Test
  void measuresTheUrlAfterPercentEncoding() {
    final FlagSelectionRequest selection = FlagSelectionRequest.of(singletonList("a"), null);
    // U+00E9 is sent as six characters, %C3%A9: five more than its string form.
    final String base = "https://cdn.example/caf\u00e9?pad=";
    final int parameterLength = "&selected_flag_tags=a".length();

    final URI atLimit = URI.create(base + repeat('x', 8192 - 5 - base.length() - parameterLength));
    final URI overLimit = URI.create(base + repeat('x', 8192 - base.length() - parameterLength));

    assertEquals(8192, selection.appendTo(atLimit).toASCIIString().length());
    assertNull(selection.appendTo(overLimit));
  }

  @TableTest({
    "scenario               | header                              | reason             ",
    "malformed_selection    | 'malformed_selection'               | malformed_selection",
    "too_many_values        | 'too_many_values'                   | too_many_values    ",
    "invalid_key_pattern    | 'invalid_key_pattern'               | invalid_key_pattern",
    "invalid_tag            | 'invalid_tag'                       | invalid_tag        ",
    "tag_load_failed        | 'tag_load_failed'                   | tag_load_failed    ",
    "wrong case             | 'TAG_LOAD_FAILED'                   | unknown            ",
    "trailing white space   | 'tag_load_failed '                  | unknown            ",
    "leading white space    | ' invalid_tag'                      | unknown            ",
    "unknown reason         | 'server_busy'                       | unknown            ",
    "33 characters          | 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx' | unknown            ",
    "known reason with tail | 'invalid_tag,invalid_tag'           | unknown            ",
    "empty                  | ''                                  |                    "
  })
  void sanitizesTheIgnoredHeader(final String header, final String reason) {
    assertEquals(reason, FlagSelectionRequest.ignoredReason(header));
  }

  @Test
  void absentIgnoredHeaderHasNoReason() {
    assertNull(FlagSelectionRequest.ignoredReason(null));
  }

  @TableTest({
    "scenario            | reason              | limit                                                                            ",
    "malformed_selection | malformed_selection | 'flagSelection must be an object with optional tags and keyPatterns string lists'",
    "too_many_values     | too_many_values     | 'each list accepts at most 31 values'                                            ",
    "invalid_key_pattern | invalid_key_pattern | 'each key pattern is at most 200 UTF-8 bytes'                                    ",
    "invalid_tag         | invalid_tag         | 'each tag is at most 200 UTF-8 bytes'                                            "
  })
  void reportsADroppedSelectionAtEachCallAndCountsItOnce(final String reason, final String limit) {
    final FlagSelectionRequest selection = dropped(reason);
    final RecordingHealthMetrics metrics = new RecordingHealthMetrics();

    selection.reportNotSent(metrics.services());
    selection.reportNotSent(metrics.services());

    final String message =
        "flagSelection was not sent ("
            + reason
            + ": "
            + limit
            + "). Loading the full flag configuration.";
    assertEquals(asList("ERROR " + message, "ERROR " + message), messages());
    assertEquals(singletonList("flag_selection.dropped|1|" + reason), metrics.counts);
  }

  @Test
  void reportsAnOverlongUrlAsTooManyValues() {
    final RecordingHealthMetrics metrics = new RecordingHealthMetrics();

    FlagSelectionRequest.urlTooLong().reportNotSent(metrics.services());

    assertEquals(
        singletonList(
            "ERROR flagSelection was not sent (too_many_values: the request URL is at most 8192"
                + " bytes). Loading the full flag configuration."),
        messages());
    assertEquals(singletonList("flag_selection.dropped|1|too_many_values"), metrics.counts);
    assertEquals("too_many_values", FlagSelectionRequest.urlTooLong().dropReason());
  }

  @Test
  void reportsNothingForASelectionThatIsSent() {
    final RecordingHealthMetrics metrics = new RecordingHealthMetrics();

    FlagSelectionRequest.NONE.reportNotSent(metrics.services());
    FlagSelectionRequest.of(singletonList("a"), null).reportNotSent(metrics.services());

    assertTrue(messages().isEmpty());
    assertTrue(metrics.counts.isEmpty());
  }

  @Test
  void reportsEachIgnoredReasonOnce() {
    final RecordingHealthMetrics metrics = new RecordingHealthMetrics();
    final RuntimeServices services = metrics.services();

    FlagSelectionRequest.reportIgnored(services, "tag_load_failed");
    FlagSelectionRequest.reportIgnored(services, "tag_load_failed");
    FlagSelectionRequest.reportIgnored(services, "unknown");

    assertEquals(
        asList(
            "ERROR flagSelection was ignored by the server (tag_load_failed). Loaded the full flag"
                + " configuration.",
            "ERROR flagSelection was ignored by the server (unknown). Loaded the full flag"
                + " configuration."),
        messages());
    assertEquals(
        asList("flag_selection.ignored|1|tag_load_failed", "flag_selection.ignored|1|unknown"),
        metrics.counts);
  }

  @Test
  void warnsOnceAboutASecondSelectionAndRemoteConfiguration() {
    FlagSelectionRequest.warnSecondSelection();
    FlagSelectionRequest.warnSecondSelection();
    FlagSelectionRequest.warnRemoteConfiguration();
    FlagSelectionRequest.warnRemoteConfiguration();

    assertEquals(
        asList(
            "WARN flagSelection differs from the flag selection already in use in this process."
                + " Using the first selection.",
            "WARN flagSelection is not supported when flags are delivered through Remote Config."
                + " Loading the full flag configuration."),
        messages());
  }

  @Test
  void failingDiagnosticsDoNotThrow() {
    CapturingServiceProvider.failOn("flagSelection");
    final RuntimeServices failingMetrics =
        new RuntimeServices((metric, value, reason) -> {
          throw new IllegalStateException("metrics backend failed");
        });

    assertDoesNotThrow(() -> dropped("invalid_tag").reportNotSent(failingMetrics));
    assertDoesNotThrow(() -> FlagSelectionRequest.reportIgnored(failingMetrics, "invalid_tag"));
    assertDoesNotThrow(FlagSelectionRequest::warnSecondSelection);
    assertDoesNotThrow(FlagSelectionRequest::warnRemoteConfiguration);
    assertFalse(messages().isEmpty());
  }

  @Test
  void countsEachReasonOncePerKind() {
    final RecordingHealthMetrics metrics = new RecordingHealthMetrics();

    dropped("too_many_values").reportNotSent(metrics.services());
    dropped("invalid_tag").reportNotSent(metrics.services());
    FlagSelectionRequest.urlTooLong().reportNotSent(metrics.services());
    FlagSelectionRequest.reportIgnored(metrics.services(), "too_many_values");

    assertEquals(
        asList(
            "flag_selection.dropped|1|too_many_values",
            "flag_selection.dropped|1|invalid_tag",
            "flag_selection.ignored|1|too_many_values"),
        metrics.counts);
  }

  private static FlagSelectionRequest dropped(final String reason) {
    switch (reason) {
      case "malformed_selection":
        return FlagSelectionRequest.of(singletonList(null), null);
      case "too_many_values":
        return FlagSelectionRequest.of(values(32, 1), null);
      case "invalid_key_pattern":
        return FlagSelectionRequest.of(null, values(1, 201));
      case "invalid_tag":
        return FlagSelectionRequest.of(values(1, 201), null);
      default:
        throw new IllegalArgumentException(reason);
    }
  }

  private static List<String> values(final int count, final int length) {
    return IntStream.range(0, count)
        .mapToObj(i -> String.format("%0" + length + "d", i))
        .collect(Collectors.toList());
  }

  private static String repeat(final char character, final int count) {
    final char[] characters = new char[count];
    Arrays.fill(characters, character);
    return new String(characters);
  }

  private static List<String> messages() {
    return CapturingServiceProvider.events().stream()
        .filter(e -> e.message.startsWith("flagSelection"))
        .map(e -> e.level + " " + e.message)
        .collect(Collectors.toList());
  }
}
