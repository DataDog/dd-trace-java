package datadog.trace.util.stacktrace;

import datadog.environment.JavaVirtualMachine;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.function.Function;
import java.util.stream.Stream;

@SuppressForbidden
public class JDK9StackWalker extends AbstractStackWalker {

  private static final java.lang.StackWalker walker;
  private static final StackMapper mapper;

  static {
    walker = newStackWalker();
    mapper = findMapper();
  }

  @Override
  public boolean isEnabled() {
    return walker != null && mapper != null;
  }

  @Override
  <T> T doGetStack(final Function<Stream<StackTraceElement>, T> consumer) {
    return walker.walk(stack -> consumer.apply(stack.map(mapper)));
  }

  /**
   * IBM J9 v.0.26.0-release was segfaulting when calling StackFrame::toStackTraceElement(), newer
   * versions are OK
   */
  private static StackTraceElement mapFrameForJ9(final java.lang.StackWalker.StackFrame frame) {
    return new StackTraceElement(
        frame.getClassName(), frame.getMethodName(), frame.getFileName(), frame.getLineNumber());
  }

  private static java.lang.StackWalker newStackWalker() {
    try {
      return java.lang.StackWalker.getInstance();
    } catch (final Throwable e) {
      // Nothing to do
      return null;
    }
  }

  private static StackMapper findMapper() {
    try {
      return JavaVirtualMachine.isJ9()
          ? JDK9StackWalker::mapFrameForJ9
          : java.lang.StackWalker.StackFrame::toStackTraceElement;
    } catch (final Throwable e) {
      // Nothing to do
      return null;
    }
  }

  private interface StackMapper
      extends Function<java.lang.StackWalker.StackFrame, StackTraceElement> {}
}
