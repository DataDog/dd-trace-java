package datadog.trace.agent.test.assertions;

import java.util.Optional;

public class IsTrue implements Matcher<Boolean> {
  @Override
  public Optional<Boolean> expected() {
    return Optional.of(true);
  }

  @Override
  public String message() {
    return "True expected";
  }

  @Override
  public boolean test(Boolean t) {
    return t;
  }
}
