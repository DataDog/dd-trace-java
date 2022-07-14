package datadog.trace.util.stacktrace;

import java.util.stream.Stream;

public class DefaultStackWalker extends AbstractStackWalker {

  DefaultStackWalker() {}

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public Stream<StackTraceElement> walk() {
    return doFilterStack(doGetStack());
  }
}
