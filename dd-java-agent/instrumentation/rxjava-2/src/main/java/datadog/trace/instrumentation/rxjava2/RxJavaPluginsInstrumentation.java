package datadog.trace.instrumentation.rxjava2;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;

@AutoService(InstrumenterModule.class)
public class RxJavaPluginsInstrumentation extends InstrumenterModule.Tracing
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
      packageName + ".RxJavaAsyncResultExtension",
    };
  }
}
