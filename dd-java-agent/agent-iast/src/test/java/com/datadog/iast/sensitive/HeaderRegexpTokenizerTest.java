package com.datadog.iast.sensitive;

import static com.google.re2j.Pattern.CASE_INSENSITIVE;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import com.datadog.iast.util.Ranged;
import com.google.re2j.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class HeaderRegexpTokenizerTest {

  private static final Pattern NAME_PATTERN =
      Pattern.compile("password|authorization", CASE_INSENSITIVE);
  private static final Pattern VALUE_PATTERN = Pattern.compile("bearer\\s", CASE_INSENSITIVE);

  @ParameterizedTest(name = "{0}")
  @MethodSource("redactsSensitiveHeadersArguments")
  void redactsSensitiveHeaders(
      final String description, final String header, final List<String> expected) {
    assertEquals(expected, tokenize(header));
  }

  static Stream<Arguments> redactsSensitiveHeadersArguments() {
    return Stream.of(
        arguments(
            "sensitive name redacts the value",
            "Authorization: Bearer xyz",
            singletonList("Bearer xyz")),
        arguments(
            "sensitive value redacts the value",
            "X-Auth: Bearer secret",
            singletonList("Bearer secret")),
        arguments("non-sensitive header is ignored", "Accept: text/html", emptyList()),
        arguments("missing separator is ignored", "NoColonHeader", emptyList()),
        arguments("missing value is ignored", "Empty:", emptyList()));
  }

  private static List<String> tokenize(String header) {
    Tokenizer tokenizer =
        new HeaderRegexpTokenizer(new Evidence(header), NAME_PATTERN, VALUE_PATTERN);
    List<String> tokens = new ArrayList<>();
    while (tokenizer.next()) {
      Ranged range = tokenizer.current();
      tokens.add(header.substring(range.getStart(), range.getStart() + range.getLength()));
    }
    return tokens;
  }
}
