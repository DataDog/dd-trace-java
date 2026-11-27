package datadog.trace.instrumentation.camel42;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.apache.camel.CamelContext;
import org.apache.camel.spi.CamelTracingService;

@AutoService(InstrumenterModule.class)
public final class CamelInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public CamelInstrumentation() {
    super("camel", "camel-4.2");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.camel.impl.engine.AbstractCamelContext";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("doInit")).and(takesArguments(0)),
        getClass().getName() + "$InitAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("addService", "removeService", "deferStartService"))
            .and(takesArgument(0, Object.class)),
        getClass().getName() + "$ServiceAdvice");
  }

  public static final class InitAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This CamelContext context) {
      CamelTracingStrategy.install(context);
    }
  }

  public static final class ServiceAdvice {
    // Lifecycle callbacks run before Camel updates its service registry.
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.This CamelContext context, @Advice.Argument(0) Object service) {
      if (service instanceof CamelTracingService) {
        CamelTracingStrategy.invalidateNativeTracing(context);
      }
    }
  }
}
