package datadog.trace.instrumentation.couchbase_31.client;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
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
      packageName + ".DatadogRequestTracer",
      packageName + ".SeedNodeHelper",
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

  private static final Reference TRACING_IDENTIFIERS_REFERENCE =
      new Reference.Builder("com.couchbase.client.core.cnc.TracingIdentifiers").build();

  private static final Reference SUSPICIOUS_EXPIRY_REFERENCE =
      new Reference.Builder(
              "com.couchbase.client.core.cnc.events.request.SuspiciousExpiryDurationEvent")
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {TRACING_IDENTIFIERS_REFERENCE, SUSPICIOUS_EXPIRY_REFERENCE};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), packageName + ".CoreEnvironmentBuilderAdvice");
  }
}
