package datadog.trace.agent.test.assertions;

import java.util.Optional;

/**
 * A generic {@link Matcher} implementation that verifies if a given value matches the expected
 * value. This matcher compares the provided input with a predefined expected value for equality.
 *
 * @param <T> The type of the value being matched.
 */
public class Is<T> implements Matcher<T> {
  private final T expected;

  Is(T expected) {
    this.expected = expected;
  }

  @Override
  public Optional<T> expected() {
    return Optional.of(this.expected);
  }

  @Override
  public String message() {
    return "Unexpected value";
  }

  @Override
  public boolean test(T t) {
    return this.expected.equals(t);
  }
}
