package datadog.trace.util.stacktrace;

import java.util.stream.Stream;

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
  public Stream<StackTraceElement> walk() {
    return walker.walk(
        (sfs ->
            sfs.filter((e) -> NOT_DD_TRACE_CLASS.test(e.getClassName()))
                .map(java.lang.StackWalker.StackFrame::toStackTraceElement)));
  }
}
