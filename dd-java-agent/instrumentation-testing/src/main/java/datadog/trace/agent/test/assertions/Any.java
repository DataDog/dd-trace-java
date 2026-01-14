package datadog.trace.agent.test.assertions;

import java.util.Optional;

/**
 * A generic {@link Matcher} implementation that always evaluates to {@code true} for any input.
 *
 * <p>This class can be used when any value is acceptable for a match. It is typically used for test
 * assertions where no specific value validation is required.
 *
 * @param <T> the type of the value being matched
 */
public class Any<T> implements Matcher<T> {
  Any() {}

  @Override
  public Optional<T> expected() {
    return Optional.empty();
  }

  @Override
  public String failureReason() {
    return "";
  }

  @Override
  public boolean test(T t) {
    return true;
  }
}
