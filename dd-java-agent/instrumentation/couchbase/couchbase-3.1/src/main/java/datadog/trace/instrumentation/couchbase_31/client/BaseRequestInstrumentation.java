package datadog.trace.instrumentation.couchbase_31.client;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(Instrumenter.class)
public class BaseRequestInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public BaseRequestInstrumentation() {
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
    return "com.couchbase.client.core.msg.BaseRequest";
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
        isConstructor().and(takesArguments(4)), packageName + ".BaseRequestAdvice");
  }
}
