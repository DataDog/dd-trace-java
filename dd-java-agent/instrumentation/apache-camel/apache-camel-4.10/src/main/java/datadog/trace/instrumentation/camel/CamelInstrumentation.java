package datadog.trace.instrumentation.camel;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.apache.camel.CamelContext;

@AutoService(InstrumenterModule.class)
public final class CamelInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public CamelInstrumentation() {
    super("camel", "camel-4.10");
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
  }

  public static final class InitAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This CamelContext context) {
      CamelTracingStrategy.install(context);
    }
  }
}
