package datadog.trace.instrumentation.rxjava2;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class RxJavaPluginsInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public RxJavaPluginsInstrumentation() {
    super("rxjava");
  }

  @Override
  protected boolean defaultEnabled() {
    // Only used with OpenTelemetry @WithSpan annotations
    return InstrumenterConfig.get().isTraceOtelEnabled();
  }

  @Override
  public String instrumentedType() {
    return "io.reactivex.plugins.RxJavaPlugins";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RxJavaAsyncResultSupportExtension",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isMethod(), getClass().getName() + "$RxJavaPluginsAdvice");
  }

  public static class RxJavaPluginsAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void init() {
      RxJavaAsyncResultSupportExtension.initialize();
    }
  }
}
