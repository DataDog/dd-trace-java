package datadog.trace.instrumentation.servicetalk0_42_56;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.servicetalk.concurrent.api.CapturedContext;
import io.servicetalk.context.api.ContextMap;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class DefaultAsyncContextProviderInstrumentation extends ServiceTalkInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.servicetalk.concurrent.api.DefaultAsyncContextProvider";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("captureContext", "captureContextCopy"),
        getClass().getName() + "$CaptureContextAdvice");
  }

  private static final class CaptureContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Return CapturedContext capturedContext) {
      ContextMap contextMap = capturedContext.captured();
      InstrumentationContext.get(ContextMap.class, AgentSpan.class)
          .put(contextMap, AgentTracer.activeSpan());
    }
  }
}
