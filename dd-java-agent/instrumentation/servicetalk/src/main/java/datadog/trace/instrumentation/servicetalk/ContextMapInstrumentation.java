package datadog.trace.instrumentation.servicetalk;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.servicetalk.context.api.ContextMap;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class ContextMapInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes {

  public ContextMapInstrumentation() {
    super("servicetalk", "servicetalk-concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.servicetalk.context.api.ContextMap", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      //      "io.servicetalk.concurrent.internal.DefaultContextMap",
      "io.servicetalk.concurrent.api.CopyOnWriteContextMap"
    };
  }

  private static final class Construct {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(@Advice.Origin Class<?> clazz) {
      int level = CallDepthThreadLocalMap.incrementCallDepth(clazz);
      return level == 0;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Enter final boolean topLevel, @Advice.This ContextMap thiz) {
      if (!topLevel) {
        return;
      }
      CallDepthThreadLocalMap.reset(thiz.getClass());

      AgentSpan activeSpan = AgentTracer.activeSpan();
      if (activeSpan != null) {
        InstrumentationContext.get(ContextMap.class, AgentSpan.class).put(thiz, activeSpan);
      }
    }
  }
}
