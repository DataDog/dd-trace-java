package locator;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.TestInstrumentation;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ClassInjectingTestInstrumentation extends TestInstrumentation {
  @Override
  public String instrumentedType() {
    return getClass().getName() + "$ToBeInstrumented";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodEnter
    public static void appendToMessage(
        @Advice.Argument(value = 0, readOnly = false) String message) {
      message = message + ":instrumented";
    }
  }

  public static final class ToBeInstrumented {
    private final String message;

    public ToBeInstrumented(String message) {
      this.message = message;
    }

    public String getMessage() {
      StringBuilder msg = new StringBuilder(message);
      for (Class<?> iface : getClass().getInterfaces()) {
        msg.append(":");
        msg.append(iface.getName());
      }
      return msg.toString();
    }
  }
}
