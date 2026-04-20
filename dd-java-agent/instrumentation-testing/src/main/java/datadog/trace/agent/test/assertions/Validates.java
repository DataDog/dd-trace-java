package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * A {@link Matcher} implementation that validates a given input value using a custom {@link
 * Predicate}. This class allows defining flexible matching criteria by providing a lambda or
 * functional interface that encapsulates the validation logic.
 *
 * @param <T> The type of the value being validated.
 */
public class Validates<T> implements Matcher<T> {
  private final Predicate<T> validator;

  Validates(Predicate<T> validator) {
    this.validator = validator;
  }

  @Override
  public Optional<T> expected() {
    return Optional.empty();
  }

  @Override
  public String failureReason() {
    return "Invalid value";
  }

  @Override
  public boolean test(T t) {
    return this.validator.test(t);
  }
}
