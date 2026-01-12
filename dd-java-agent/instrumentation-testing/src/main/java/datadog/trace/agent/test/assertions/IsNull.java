package datadog.trace.agent.test.assertions;

import java.util.Optional;

/**
 * A {@link Matcher} implementation that checks if a given value is {@code null}.
 *
 * @param <T> The type of the value being matched.
 */
public class IsNull<T> implements Matcher<T> {
  @Override
  public Optional<T> expected() {
    return Optional.empty();
  }

  @Override
  public String message() {
    return "Null value expected";
  }

  @Override
  public boolean test(T t) {
    return t == null;
  }
}
