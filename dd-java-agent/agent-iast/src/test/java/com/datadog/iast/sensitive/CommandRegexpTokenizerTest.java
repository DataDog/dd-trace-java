package com.datadog.iast.sensitive;

import static java.util.Arrays.asList;
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

class CommandRegexpTokenizerTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("redactsCommandArgumentsArguments")
  void redactsCommandArguments(
      final String description, final String command, final List<String> expected) {
    assertEquals(expected, tokenize(command));
  }

  static Stream<Arguments> redactsCommandArgumentsArguments() {
    return Stream.of(
        arguments("plain command keeps its arguments", "ls -la /tmp", asList("-la /tmp")),
        arguments("sudo prefix is skipped", "sudo rm -rf /", asList("-rf /")),
        arguments("doas prefix is skipped", "doas cat /etc/passwd", asList("/etc/passwd")),
        arguments(
            "everything after the binary is captured", "echo hello world", asList("hello world")));
  }

  private static List<String> tokenize(String command) {
    Tokenizer tokenizer = new CommandRegexpTokenizer(new Evidence(command));
    List<String> tokens = new ArrayList<>();
    while (tokenizer.next()) {
      Ranged range = tokenizer.current();
      tokens.add(command.substring(range.getStart(), range.getStart() + range.getLength()));
    }
    return tokens;
  }
}
