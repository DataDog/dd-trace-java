package datadog.test.agent.assertions;

import java.util.Optional;
import java.util.regex.Pattern;

public class Matches implements Matcher<String> {
  private final Pattern pattern;

  Matches(Pattern pattern) {
    this.pattern = pattern;
  }

  @Override
  public Optional<String> expected() {
    return Optional.empty();
  }

  @Override
  public String message() {
    return "Non matching value";
  }

  @Override
  public boolean test(String s) {
    return this.pattern.matcher(s).matches();
  }
}
