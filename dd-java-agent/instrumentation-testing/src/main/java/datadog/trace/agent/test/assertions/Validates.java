package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.function.Predicate;

public class Validates<T> implements Matcher<T> {
  private final Predicate<T> validator;

  public Validates(Predicate<T> validator) {
    this.validator = validator;
  }

  @Override
  public Optional<T> expected() {
    return Optional.empty();
  }

  @Override
  public String message() {
    return "Invalid value";
  }

  @Override
  public boolean test(T t) {
    return this.validator.test(t);
  }
}
