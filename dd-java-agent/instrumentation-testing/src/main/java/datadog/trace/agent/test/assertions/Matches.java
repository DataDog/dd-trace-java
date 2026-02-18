package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A matcher implementation that checks if strings (as {@link CharSequence}) match a specified
 * {@link Pattern}. This class is used for validating whether a string conforms to a specific
 * regular expression.
 */
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
  public String failureReason() {
    return "Non matching value";
  }

  @Override
  public boolean test(CharSequence s) {
    return this.pattern.matcher(s).matches();
  }
}
