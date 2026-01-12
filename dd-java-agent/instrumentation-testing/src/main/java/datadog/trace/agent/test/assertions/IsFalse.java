package datadog.trace.agent.test.assertions;

import java.util.Optional;

/** A {@link Matcher} implementation that checks if a given boolean value is {@code false}. */
public class IsFalse implements Matcher<Boolean> {
  @Override
  public Optional<Boolean> expected() {
    return Optional.of(false);
  }

  @Override
  public String message() {
    return "False expected";
  }

  @Override
  public boolean test(Boolean t) {
    return !t;
  }
}
