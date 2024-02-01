package datadog.trace.instrumentation.couchbase_32.client;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

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
      packageName + ".DatadogRequestSpan$1",
      packageName + ".DatadogRequestTracer",
    };
  }

  @Override
  public String instrumentedType() {
    return "com.couchbase.client.core.msg.BaseRequest";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(4)), packageName + ".BaseRequestAdvice");
  }
}
