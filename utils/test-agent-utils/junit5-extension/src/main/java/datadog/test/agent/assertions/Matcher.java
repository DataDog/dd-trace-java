package datadog.test.agent.assertions;

import java.util.Optional;
import java.util.function.Predicate;

public interface Matcher<T> extends Predicate<T> {
  Optional<T> expected();
  String message();
}
