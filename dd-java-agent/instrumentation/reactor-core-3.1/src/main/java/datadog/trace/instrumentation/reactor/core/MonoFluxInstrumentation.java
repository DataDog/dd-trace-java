package datadog.trace.instrumentation.reactor.core;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class MonoFluxInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {
  public MonoFluxInstrumentation() {
    super("reactor-core");
  }

  @Override
  protected boolean defaultEnabled() {
    // Only used with OpenTelemetry @WithSpan annotations
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"reactor.core.publisher.Flux", "reactor.core.publisher.Mono"};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ReactorAsyncResultSupportExtension",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), this.getClass().getName() + "$AsyncTypeAdvice");
  }

  public static class AsyncTypeAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void init() {
      ReactorAsyncResultSupportExtension.initialize();
    }
  }
}
