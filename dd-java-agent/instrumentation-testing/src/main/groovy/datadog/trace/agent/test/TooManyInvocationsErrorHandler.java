package datadog.trace.agent.test;

import java.util.List;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.spockframework.mock.IMockInvocation;
import org.spockframework.mock.TooManyInvocationsError;

/**
 * This extension fixes tests failures when a {@link
 * org.spockframework.mock.TooManyInvocationsError} triggers a {@link java.lang.StackOverflowError}
 * while composing the failure message.
 *
 * <p>When a mocked class captures the assertion error (e.g. {@link
 * org.spockframework.mock.TooManyInvocationsError}), Spock will throw an {@link
 * java.lang.StackOverflowError} while building the accepted invocations failure message, which
 * causes the test to be ignored and won't be reported as a failure.
 *
 * @see <a href="https://github.com/DataDog/dd-trace-java/pull/7674">Original change</a>
 */
public final class TooManyInvocationsErrorHandler implements TestExecutionExceptionHandler {

  @Override
  public void handleTestExecutionException(ExtensionContext ctx, Throwable ex) throws Throwable {
    if (ex instanceof TooManyInvocationsError) {
      fixTooManyInvocationsError((TooManyInvocationsError) ex);
      throw ex; // re‑throw so JUnit still marks the test as failed.
    }
    throw ex;
  }

  static void fixTooManyInvocationsError(final TooManyInvocationsError error) {
    final List<IMockInvocation> accepted = error.getAcceptedInvocations();
    for (final IMockInvocation invocation : accepted) {
      try {
        invocation.toString();
      } catch (final Throwable t) {
        final List<Object> args = invocation.getArguments();
        for (int i = 0; i < args.size(); i++) {
          final Object arg = args.get(i);
          if (arg instanceof AssertionError) {
            args.set(
                i,
                new AssertionError(
                    "'"
                        + arg.getClass().getName()
                        + "' hidden due to '"
                        + t.getClass().getName()
                        + "'",
                    t));
          }
        }
      }
    }
  }
}
