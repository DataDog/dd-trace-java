package datadog.trace.util.stacktrace;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.function.Function;
import java.util.stream.Stream;

@SuppressForbidden
public class JDK9StackWalker extends AbstractStackWalker {

  static java.lang.StackWalker walker;

  static {
    try {
      walker = java.lang.StackWalker.getInstance();

    } catch (Throwable e) {
      // Nothing to do
    }
  }

  @Override
  public boolean isEnabled() {
    return walker != null;
  }

  @Override
  <T> T doGetStack(final Function<Stream<StackTraceElement>, T> consumer) {
    return walker.walk(
        stack -> consumer.apply(stack.map(java.lang.StackWalker.StackFrame::toStackTraceElement)));
  }
}
