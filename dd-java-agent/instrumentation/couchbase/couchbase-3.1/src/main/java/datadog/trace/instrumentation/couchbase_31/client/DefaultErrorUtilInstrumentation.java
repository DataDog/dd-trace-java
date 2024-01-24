package datadog.trace.instrumentation.couchbase_31.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(Instrumenter.class)
public class DefaultErrorUtilInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public DefaultErrorUtilInstrumentation() {
    super("couchbase", "couchbase-3");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CouchbaseClientDecorator",
      packageName + ".DatadogRequestSpan",
      packageName + ".DatadogRequestTracer",
    };
  }

  @Override
  public String instrumentedType() {
    return "com.couchbase.client.core.error.DefaultErrorUtil";
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
    transformer.applyAdvice(
        isStatic().and(isMethod()).and(named("keyValueStatusToException")),
        packageName + ".DefaultErrorUtilAdvice");
  }
}
