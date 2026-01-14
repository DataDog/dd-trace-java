package datadog.trace.agent.test.assertions;

import java.util.Optional;

/**
 * A {@link Matcher} implementation that checks if a given value is not {@code null}.
 *
 * @param <T> The type of the value being matched.
 */
public class IsNonNull<T> implements Matcher<T> {
  IsNonNull() {}

  @Override
  public Optional<T> expected() {
    return Optional.empty();
  }

  @Override
  public String failureReason() {
    return "Non-null value expected";
  }

  @Override
  public boolean test(T t) {
    return t != null;
  }
}
