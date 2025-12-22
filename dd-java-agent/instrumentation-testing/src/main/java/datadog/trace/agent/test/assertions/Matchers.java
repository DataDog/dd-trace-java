package datadog.trace.agent.test.assertions;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.opentest4j.AssertionFailedError;

/*
 * TODO: Dev notes
 * - introduce as few as possible matchers
 * - only have matchers for generic purpose, don't introduce feature / produce / use-case specific matchers
 * - name "ignores" as "any"?
 */

public class Matchers {
  public static <T> Matcher<T> is(T expected) {
    return new Is<>(expected);
  }

  public static <T> Matcher<T> isNull() {
    return new IsNull<>();
  }

  public static <T> Matcher<T> nonNull() {
    return new IsNonNull<>();
  }

  public static Matcher<Boolean> isTrue() {
    return new IsTrue();
  }

  public static Matcher<Boolean> isFalse() {
    return new IsFalse();
  }

  public static Matcher<CharSequence> matches(String regex) {
    return new Matches(Pattern.compile(regex));
  }

  public static Matcher<CharSequence> matches(Pattern pattern) {
    return new Matches(pattern);
  }

  public static <T> Matcher<T> validates(Predicate<T> validator) {
    return new Validates<>(validator);
  }

  public static <T> Matcher<T> any() {
    return new Any<>();
  }

  static <T> void assertValue(Matcher<T> matcher, T value, String message) {
    if (matcher != null && !matcher.test(value)) {
      Optional<T> expected = matcher.expected();
      if (expected.isPresent()) {
        throw new AssertionFailedError(message + ". " + matcher.message(), expected.get(), value);
      } else {
        throw new AssertionFailedError(message + ": " + value + ". " + matcher.message());
      }
    }
  }
}
