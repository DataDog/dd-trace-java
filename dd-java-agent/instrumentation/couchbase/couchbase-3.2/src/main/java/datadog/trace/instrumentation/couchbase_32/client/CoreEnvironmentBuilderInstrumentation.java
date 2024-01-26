package datadog.trace.instrumentation.couchbase_32.client;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Collections;
import java.util.Map;

@AutoService(Instrumenter.class)
public class CoreEnvironmentBuilderInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
  }
}
