package datadog.trace.instrumentation.mule4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.api.component.Component;
import org.mule.runtime.tracer.api.span.info.InitialSpanInfo;
import org.mule.runtime.tracer.customization.impl.info.ExecutionInitialSpanInfo;

@AutoService(InstrumenterModule.class)
public class ExecutionInitialSpanInfoInstrumentation extends AbstractMuleInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.mule.runtime.tracer.customization.impl.info.ExecutionInitialSpanInfo";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, named("org.mule.runtime.api.component.Component"))),
        getClass().getName() + "$StoreComponentAdvice");
  }

  public static class StoreComponentAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruct(
        @Advice.This ExecutionInitialSpanInfo self, @Advice.Argument(0) final Component component) {
      InstrumentationContext.get(InitialSpanInfo.class, Component.class).put(self, component);
    }
  }
}
