package datadog.trace.instrumentation.couchbase.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.couchbase.client.core.message.CouchbaseRequest;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class CouchbaseCoreInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CouchbaseCoreInstrumentation() {
    super("couchbase");
  }

  @Override
  public String instrumentedType() {
    return "com.couchbase.client.core.CouchbaseCore";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "com.couchbase.client.core.message.CouchbaseRequest", AgentSpan.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(takesArgument(0, named("com.couchbase.client.core.message.CouchbaseRequest")))
            .and(named("send")),
        CouchbaseCoreInstrumentation.class.getName() + "$CouchbaseCoreAdvice");
  }

  public static class CouchbaseCoreAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addOperationIdToSpan(@Advice.Argument(0) final CouchbaseRequest request) {

      final AgentSpan span = activeSpan();
      if (span != null) {
        // The context from the initial rxJava subscribe is not available to the networking layer
        // To transfer the span, the span is added to the context store

        final ContextStore<CouchbaseRequest, AgentSpan> contextStore =
            InstrumentationContext.get(CouchbaseRequest.class, AgentSpan.class);

        if (contextStore.get(request) == null) {
          contextStore.put(request, span);

          if (request.operationId() != null) {
            span.setTag("couchbase.operation_id", request.operationId());
          }
        }
      }
    }
  }
}
