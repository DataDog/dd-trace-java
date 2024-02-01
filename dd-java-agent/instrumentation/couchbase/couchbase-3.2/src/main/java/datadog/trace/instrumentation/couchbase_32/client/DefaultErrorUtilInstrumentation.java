package datadog.trace.instrumentation.couchbase_32.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

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
      packageName + ".DatadogRequestSpan$1",
      packageName + ".DatadogRequestTracer",
    };
  }

  @Override
  public String instrumentedType() {
    return "com.couchbase.client.core.error.DefaultErrorUtil";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isStatic().and(isMethod()).and(named("keyValueStatusToException")),
        packageName + ".DefaultErrorUtilAdvice");
  }
}
