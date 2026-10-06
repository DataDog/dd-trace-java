package cartography;

import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/** Capture only invocation bodies, matching the existing instrumentation test harness. */
public final class TestWindow implements InvocationInterceptor {
  @Override
  public void interceptTestMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> method,
      ExtensionContext context)
      throws Throwable {
    Recorder.begin(
        context.getUniqueId(),
        context.getRequiredTestClass().getName() + "#" + context.getRequiredTestMethod().getName());
    try {
      invocation.proceed();
    } finally {
      Recorder.end();
    }
  }

  @Override
  public void interceptTestTemplateMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> method,
      ExtensionContext context)
      throws Throwable {
    interceptTestMethod(invocation, method, context);
  }
}
