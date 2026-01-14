package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.opentest4j.AssertionFailedError;

/** This class is a utility class to create generic matchers. */
public final class Matchers {
  private Matchers() {}

  /**
   * Creates a matcher that checks if the provided value is equal to the expected value.
   *
   * @param <T> The type of the value being matched.
   * @param expected The value to compare against
   * @return A {@link Matcher} that verifies equality with the expected value.
   */
  public static <T> Matcher<T> is(T expected) {
    return new Is<>(expected);
  }

  /**
   * Creates a matcher that checks if a given value is {@code null}.
   *
   * @param <T> The type of the value being matched.
   * @return A {@link Matcher} that verifies the value is {@code null}.
   */
  public static <T> Matcher<T> isNull() {
    return new IsNull<>();
  }

  /**
   * Creates a matcher that checks if a given value is not {@code null}.
   *
   * @param <T> The type of the value being matched.
   * @return A {@link Matcher} that verifies the value is not {@code null}.
   */
  public static <T> Matcher<T> isNonNull() {
    return new IsNonNull<>();
  }

  /**
   * Creates a matcher that checks if a given boolean value is {@code true}.
   *
   * @return A {@link Matcher} that verifies the value is {@code true}.
   */
  public static Matcher<Boolean> isTrue() {
    return new IsTrue();
  }

  /**
   * Creates a matcher that checks if a given boolean value is {@code false}.
   *
   * @return A {@link Matcher} that verifies the value is {@code false}.
   */
  public static Matcher<Boolean> isFalse() {
    return new IsFalse();
  }

  /**
   * Creates a {@link Matcher} that checks if a given string matches a specified regular expression.
   *
   * @param regex The regular expression pattern used for matching.
   * @return A {@link Matcher} that validates if a string matches the provided regular expression.
   */
  public static Matcher<CharSequence> matches(String regex) {
    return new Matches(Pattern.compile(regex));
  }

  /**
   * Creates a {@link Matcher} that checks if a given string matches a specified {@link Pattern}.
   *
   * @param pattern The regular expression pattern used for matching.
   * @return A {@link Matcher} that validates if a string matches the provided {@link Pattern}.
   */
  public static Matcher<CharSequence> matches(Pattern pattern) {
    return new Matches(pattern);
  }

  /**
   * Creates a {@link Matcher} that validates a given value based on the provided {@link Predicate}.
   * This method allows specifying custom validation logic for matching input values.
   *
   * @param <T> The type of the value being validated.
   * @param validator A {@link Predicate} representing the custom validation logic to be applied.
   * @return A {@link Matcher} that uses the provided {@link Predicate} to validate input values.
   */
  public static <T> Matcher<T> validates(Predicate<T> validator) {
    return new Validates<>(validator);
  }

  /**
   * Creates a matcher that always accepts any input value.
   *
   * @param <T> The type of the value being matched.
   * @return A {@link Matcher} that accepts any value and always matches.
   */
  public static <T> Matcher<T> any() {
    return new Any<>();
  }

  static <T> void assertValue(Matcher<T> matcher, T value, String message) {
    if (matcher != null && !matcher.test(value)) {
      Optional<T> expected = matcher.expected();
      if (expected.isPresent()) {
        throw new AssertionFailedError(
            message + ". " + matcher.failureReason(), expected.get(), value);
      } else {
        throw new AssertionFailedError(message + ": " + value + ". " + matcher.failureReason());
      }
    }
  }
}
