package datadog.trace.util.stacktrace;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

public class DefaultStackWalker extends AbstractStackWalker {

  DefaultStackWalker() {}

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  <T> T doGetStack(final Function<Stream<StackTraceElement>, T> consumer) {
    return consumer.apply(Arrays.stream(new Throwable().getStackTrace()));
  }
}
