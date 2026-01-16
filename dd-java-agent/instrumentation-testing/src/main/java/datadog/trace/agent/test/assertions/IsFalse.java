package datadog.trace.agent.test.assertions;

import java.util.Optional;

/** A {@link Matcher} implementation that checks if a given boolean value is {@code false}. */
public class IsFalse implements Matcher<Boolean> {
  IsFalse() {}

  @Override
  public Optional<Boolean> expected() {
    return Optional.of(false);
  }

  @Override
  public String failureReason() {
    return "False expected";
  }

  @Override
  public boolean test(Boolean t) {
    return !t;
  }
}
