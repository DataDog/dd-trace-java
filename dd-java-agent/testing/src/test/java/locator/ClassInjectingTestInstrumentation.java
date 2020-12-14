package locator;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.agent.tooling.bytebuddy.ExceptionHandlers;
import java.util.Set;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ClassInjectingTestInstrumentation implements Instrumenter {
  @Override
  public AgentBuilder instrument(AgentBuilder agentBuilder) {
    return agentBuilder
        .type(named(getClass().getName() + "$ToBeInstrumented"))
        .transform(
            new AgentBuilder.Transformer.ForAdvice()
                .include(Utils.getBootstrapProxy(), Utils.getAgentClassLoader())
                .withExceptionHandler(ExceptionHandlers.defaultExceptionHandler())
                .advice(isConstructor(), getClass().getName() + "$ConstructorAdvice"));
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    // don't care
    return true;
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
