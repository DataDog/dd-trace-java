package datadog.trace.util.stacktrace;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefaultStackWalker implements StackWalker {

  DefaultStackWalker() {}

  protected static Set<String> filteredPackages =
      Stream.of("datadog.trace.", "com.datadog.appsec.")
          .collect(Collectors.toCollection(HashSet::new));

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public Stream<StackTraceElement> walk() {
    return doGetStack().filter(e -> isNotDDTraceClass(e.getClassName()));
  }

  static boolean isNotDDTraceClass(final String className) {
    return filteredPackages.stream().noneMatch(className::startsWith);
  }

  protected Stream<StackTraceElement> doGetStack() {
    return Arrays.stream(new Throwable().getStackTrace());
  }
}
