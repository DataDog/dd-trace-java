package locator;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.test.base.TestInstrumentation;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ClassInjectingTestInstrumentation extends TestInstrumentation
    implements Instrumenter.WithTypeStructure {

  @Override
  public String instrumentedType() {
    return getClass().getName() + "$ToBeInstrumented";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    // additional constraint which requires loading the InjectedInterface to match
    return hasInterface(declaresAnnotation(named(getClass().getName() + "$ToBeMatched")));
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

  @Retention(RetentionPolicy.RUNTIME)
  public @interface ToBeMatched {}

  public static final class ToBeInstrumented {
    private final String message;

    public ToBeInstrumented(String message) {
      this.message = message;
    }

    public String getMessage() {
      StringBuilder msg = new StringBuilder(message);
      for (Class<?> iface : getClass().getInterfaces()) {
        msg.append(':');
        msg.append(iface.getName());
      }
      return msg.toString();
    }
  }
}
