package com.datadog.iast.sensitive;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.util.Ranged;
import java.util.regex.Pattern;

public class CommandRegexpTokenizer extends AbstractRegexTokenizer {

  private static final Pattern COMMAND_PATTERN =
      Pattern.compile(
          "^(?:\\s*(?:sudo|doas)\\s+)?\\b\\S+\\b\\s*(.*)", Pattern.MULTILINE | Pattern.DOTALL);

  public CommandRegexpTokenizer(final Evidence evidence) {
    super(COMMAND_PATTERN, evidence.getValue());
  }

  @Override
  protected Ranged buildNext() {
    final int start = matcher.start(1);
    return Ranged.build(start, matcher.end(1) - start);
  }
}
