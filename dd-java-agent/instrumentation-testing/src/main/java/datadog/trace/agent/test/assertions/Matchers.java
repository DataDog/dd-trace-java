package datadog.trace.agent.test.assertions;

import java.util.function.Predicate;
import java.util.regex.Pattern;

public class Matchers {
  public static <T> Matcher<T> is(T expected) {
    return new Is<>(expected);
  }

  public static <T> Matcher<T> nonNull() {
    return new NonNull<>();
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
}
