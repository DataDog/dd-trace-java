package datadog.trace.instrumentation.couchbase_32.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class CoreEnvironmentBuilderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CoreEnvironmentBuilderInstrumentation() {
    super("couchbase", "couchbase-3");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CouchbaseClientDecorator",
      packageName + ".DatadogRequestSpan",
      packageName + ".DatadogRequestSpan$1",
      packageName + ".DatadogRequestTracer",
      packageName + ".DelegatingRequestSpan",
      packageName + ".DelegatingRequestTracer"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.couchbase.client.core.Core", String.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "com.couchbase.client.core.env.CoreEnvironment$Builder";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".CoreEnvironmentBuilderAdvice");
    transformer.applyAdvice(
        isMethod().and(named("requestTracer")),
        packageName + ".CoreEnvironmentBuilderRequestTracerAdvice");
  }
}
