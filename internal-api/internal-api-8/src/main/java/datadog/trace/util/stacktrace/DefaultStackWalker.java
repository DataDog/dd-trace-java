package datadog.trace.util.stacktrace;

import java.util.Arrays;
import java.util.stream.Stream;

public class DefaultStackWalker extends AbstractStackWalker {

  DefaultStackWalker() {}

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  Stream<StackTraceElement> doGetStack() {
    return Arrays.stream(new Throwable().getStackTrace());
  }
}
