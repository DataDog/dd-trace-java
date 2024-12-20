package datadog.test.agent.assertions;

import java.util.Optional;

public class NonNull<T> implements Matcher<T> {
  @Override
  public Optional<T> expected() {
    return Optional.empty();
  }

  @Override
  public String message() {
    return "Non-null value expected";
  }

  @Override
  public boolean test(T t) {
    return t != null;
  }
}
