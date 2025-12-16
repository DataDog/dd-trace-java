package datadog.trace.agent.test.assertions;

import java.util.Optional;

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
