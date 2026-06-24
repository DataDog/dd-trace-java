package com.datadog.iast.sensitive;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import com.datadog.iast.util.Ranged;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UrlRegexpTokenizerTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("redactsUrlSecretsArguments")
  void redactsUrlSecrets(final String description, final String url, final List<String> expected) {
    assertEquals(expected, tokenize(url));
  }

  static Stream<Arguments> redactsUrlSecretsArguments() {
    return Stream.of(
        arguments("userinfo authority", "https://user:pass@host/path", singletonList("user:pass")),
        arguments("single user authority", "ftp://bob@server/file", singletonList("bob")),
        arguments(
            "query parameter values", "http://h/p?token=secret&id=42", asList("secret", "42")),
        arguments("authority and query together", "https://user@host/p?q=v", asList("user", "v")));
  }

  private static List<String> tokenize(String url) {
    Tokenizer tokenizer = new UrlRegexpTokenizer(new Evidence(url));
    List<String> tokens = new ArrayList<>();
    while (tokenizer.next()) {
      Ranged range = tokenizer.current();
      tokens.add(url.substring(range.getStart(), range.getStart() + range.getLength()));
    }
    return tokens;
  }
}
