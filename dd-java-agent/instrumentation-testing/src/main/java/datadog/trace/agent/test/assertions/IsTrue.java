package datadog.trace.agent.test.assertions;

import java.util.Optional;

/** A {@link Matcher} implementation that checks if a given boolean value is {@code true}. */
public class IsTrue implements Matcher<Boolean> {
  IsTrue() {}

  @Override
  public Optional<Boolean> expected() {
    return Optional.of(true);
  }

  @Override
  public String failureReason() {
    return "True expected";
  }

  @Override
  public boolean test(Boolean t) {
    return t;
  }
}
