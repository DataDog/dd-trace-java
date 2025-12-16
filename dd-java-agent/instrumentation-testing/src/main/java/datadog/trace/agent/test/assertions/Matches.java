package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.regex.Pattern;

public class Matches implements Matcher<CharSequence> {
  private final Pattern pattern;

  Matches(Pattern pattern) {
    this.pattern = pattern;
  }

  @Override
  public Optional<CharSequence> expected() {
    return Optional.empty();
  }

  @Override
  public String message() {
    return "Non matching value";
  }

  @Override
  public boolean test(CharSequence s) {
    return this.pattern.matcher(s).matches();
  }
}
