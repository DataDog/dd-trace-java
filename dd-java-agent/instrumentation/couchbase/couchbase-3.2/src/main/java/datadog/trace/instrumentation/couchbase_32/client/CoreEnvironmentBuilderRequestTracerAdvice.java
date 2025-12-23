package datadog.trace.instrumentation.couchbase_32.client;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.cnc.RequestTracer;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

public class CoreEnvironmentBuilderRequestTracerAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.Argument(value = 0, readOnly = false) RequestTracer requestTracer) {

    // already a delegating tracer
    if (requestTracer instanceof DelegatingRequestTracer) {
      return;
    }

    // already a datadog tracer
    if (requestTracer instanceof DatadogRequestTracer) {
      return;
    }

    ContextStore<Core, String> coreContext = InstrumentationContext.get(Core.class, String.class);

    DatadogRequestTracer datadogTracer = new DatadogRequestTracer(AgentTracer.get(), coreContext);

    // if the app didn't set a custom tracer, use only datadog tracer
    if (requestTracer == null) {
      requestTracer = datadogTracer;
      return;
    }

    // Wrap custom datadog and cnc tracers into a delegating
    requestTracer = new DelegatingRequestTracer(datadogTracer, requestTracer);
  }
}
