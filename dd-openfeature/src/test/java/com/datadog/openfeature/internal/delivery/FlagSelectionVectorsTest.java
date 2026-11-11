package com.datadog.openfeature.internal.delivery;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadog.openfeature.internal.JsonReading;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the cross-SDK flag selection vectors from the {@code ffe-system-test-data} submodule. The
 * {@code rawQuery} cases exercise the server's percent-decoding, so they have no SDK expectation.
 */
class FlagSelectionVectorsTest {
  private static final String VECTORS_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data/flag-selection/selection-vectors.json";
  private static final URI ENDPOINT =
      URI.create(
          "https://ufc-server.ff-cdn.datadoghq.com/api/v2/feature-flagging/config/rules-based/server?dd_env=prod");

  // No exact counts: the submodule can add vector cases at any time.
  @Test
  void runsEverySdkCaseAndSkipsOnlyTheRawQueryCases() throws IOException {
    final List<Map<String, Object>> cases = vectorCases();

    assertTrue(cases.stream().anyMatch(c -> c.containsKey("sdk")), "the vectors have no SDK case");
    for (final Map<String, Object> testCase : cases) {
      assertTrue(
          testCase.containsKey("sdk") != testCase.containsKey("rawQuery"),
          "exactly one of sdk or rawQuery: " + testCase.get("name"));
    }
  }

  static Stream<Arguments> cases() throws IOException {
    return vectorCases().stream().map(c -> Arguments.of(c.get("name"), c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @SuppressWarnings("unchecked")
  void sendsTheCanonicalSelectionOrDropsIt(final String name, final Map<String, Object> testCase) {
    assumeTrue(testCase.containsKey("sdk"), "the server's percent-decoding has no SDK expectation");
    final Map<String, Object> query = (Map<String, Object>) testCase.get("query");
    final Map<String, Object> sdk = (Map<String, Object>) testCase.get("sdk");

    final FlagSelectionRequest selection =
        FlagSelectionRequest.of(
            (List<?>) query.get("selected_flag_tags"),
            (List<?>) query.get("selected_flag_key_patterns"));

    if (sdk.containsKey("dropped")) {
      assertEquals(sdk.get("dropped"), selection.dropReason());
      assertEquals(ENDPOINT, selection.appendTo(ENDPOINT), "a dropped selection is not sent");
      return;
    }
    final Map<String, Object> expected = (Map<String, Object>) sdk.get("selection");
    final List<String> tags = (List<String>) expected.get("tags");
    final List<String> keyPatterns = (List<String>) expected.get("keyPatterns");
    assertNull(selection.dropReason());
    assertEquals(tags, selection.tags());
    assertEquals(keyPatterns, selection.keyPatterns());
    assertEquals(tags.isEmpty() && keyPatterns.isEmpty(), selection.isNone());

    final URI sent = selection.appendTo(ENDPOINT);
    assertEquals(ENDPOINT.getRawPath(), sent.getRawPath());
    final List<String> parameters = new ArrayList<>();
    parameters.add("dd_env=prod");
    tags.forEach(tag -> parameters.add("selected_flag_tags=" + tag));
    keyPatterns.forEach(pattern -> parameters.add("selected_flag_key_patterns=" + pattern));
    assertEquals(parameters, decodedParameters(sent));
    assertFalse(sent.getRawQuery().contains("+"), "values are percent-encoded, not form-encoded");
  }

  private static List<String> decodedParameters(final URI uri) {
    final List<String> parameters = new ArrayList<>();
    for (final String parameter : uri.getRawQuery().split("&")) {
      final int separator = parameter.indexOf('=');
      parameters.add(
          parameter.substring(0, separator)
              + "="
              + URLDecoder.decode(parameter.substring(separator + 1), UTF_8));
    }
    return parameters;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> vectorCases() throws IOException {
    final Map<String, Object> vectors = JsonReading.readObject(Files.readAllBytes(vectorsFile()));
    final Object cases = vectors.get("cases");
    return cases == null ? emptyList() : (List<Map<String, Object>>) cases;
  }

  private static Path vectorsFile() {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve(VECTORS_PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Unable to find the flag selection vectors: " + VECTORS_PATH);
  }
}
