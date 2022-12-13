package datadog.trace.instrumentation.couchbase_31.client;

import com.couchbase.client.core.env.CoreEnvironment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

public class CoreEnvironmentBuilderAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(@Advice.This CoreEnvironment.Builder<?> builder) {
    builder.requestTracer(new DatadogRequestTracer(AgentTracer.get()));
  }
}
