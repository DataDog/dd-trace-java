package datadog.trace.agent.test.assertions;

import java.util.Optional;

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
