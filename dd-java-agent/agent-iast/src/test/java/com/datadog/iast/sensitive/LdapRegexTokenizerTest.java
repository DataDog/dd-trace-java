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

class LdapRegexTokenizerTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("redactsFilterLiteralsArguments")
  void redactsFilterLiterals(
      final String description, final String filter, final List<String> expected) {
    assertEquals(expected, tokenize(filter));
  }

  static Stream<Arguments> redactsFilterLiteralsArguments() {
    return Stream.of(
        arguments("equality literal", "(cn=John Doe)", singletonList("John Doe")),
        arguments("nested filter literals", "(&(uid=bob)(role=admin))", asList("bob", "admin")),
        arguments("greater-or-equal operator", "(age>=21)", singletonList("21")),
        arguments("less-or-equal operator", "(score<=100)", singletonList("100")),
        arguments("approximate operator", "(attr~=approx)", singletonList("approx")));
  }

  private static List<String> tokenize(String filter) {
    Tokenizer tokenizer = new LdapRegexTokenizer(new Evidence(filter));
    List<String> tokens = new ArrayList<>();
    while (tokenizer.next()) {
      Ranged range = tokenizer.current();
      tokens.add(filter.substring(range.getStart(), range.getStart() + range.getLength()));
    }
    return tokens;
  }
}
