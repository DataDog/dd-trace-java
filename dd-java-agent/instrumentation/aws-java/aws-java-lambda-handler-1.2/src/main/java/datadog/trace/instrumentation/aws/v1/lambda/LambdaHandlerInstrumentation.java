package datadog.trace.instrumentation.aws.v1.lambda;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.aws.v1.lambda.LambdaHandlerDecorator.INVOCATION_SPAN_NAME;
import static net.bytebuddy.asm.Advice.Enter;
import static net.bytebuddy.asm.Advice.OnMethodEnter;
import static net.bytebuddy.asm.Advice.OnMethodExit;
import static net.bytebuddy.asm.Advice.Origin;
import static net.bytebuddy.asm.Advice.This;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.config.inversion.ConfigHelper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class LambdaHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  // these must remain as String literals so they can be easily be shared (copied) with the nested
  // advice classes
  private static final String HANDLER_ENV_NAME = "_HANDLER";

  public LambdaHandlerInstrumentation() {
    super("aws-lambda");
  }

  @Override
  public String hierarchyMarkerType() {
    return "com.amazonaws.services.lambda.runtime.RequestStreamHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(
        named(hierarchyMarkerType())
            .or(named("com.amazonaws.services.lambda.runtime.RequestHandler")));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LambdaHandlerDecorator",
    };
  }

  @Override
  protected boolean defaultEnabled() {
    return ConfigHelper.env(HANDLER_ENV_NAME) != null;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // lambda under the hood converts all handlers to streaming handlers via
    // lambdainternal.EventHandlerLoader$PojoHandlerAsStreamHandler.handleRequest
    // full spec here : https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html
    transformer.applyAdvice(
        isMethod()
            .and(named("handleRequest"))
            .and(takesArgument(2, named("com.amazonaws.services.lambda.runtime.Context"))),
        getClass().getName() + "$ExtensionCommunicationAdvice");
  }

  public static class ExtensionCommunicationAdvice {
    @OnMethodEnter
    static AgentScope enter(
        @This final Object that,
        @Advice.Argument(0) final Object in,
        @Advice.Argument(1) final Object out,
        @Advice.Argument(2) final Context awsContext,
        @Origin("#m") final String methodName) {

      if (CallDepthThreadLocalMap.incrementCallDepth(RequestHandler.class) > 0) {
        return null;
      }
      String lambdaRequestId = awsContext.getAwsRequestId();
      AgentSpanContext lambdaContext = AgentTracer.get().notifyLambdaStart(in, lambdaRequestId);
      final AgentSpan span;
      if (null == lambdaContext) {
        span = startSpan("java-aws-sdk", INVOCATION_SPAN_NAME);
      } else {
        span = startSpan("java-aws-sdk", INVOCATION_SPAN_NAME, lambdaContext);
      }
      span.setSpanType(InternalSpanTypes.SERVERLESS);
      span.setTag("request_id", lambdaRequestId);

      final AgentScope scope = activateSpan(span);
      return scope;
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Origin String method,
        @Enter final AgentScope scope,
        @Advice.Argument(1) final Object result,
        @Advice.Argument(2) final Context awsContext,
        @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }

      CallDepthThreadLocalMap.reset(RequestHandler.class);

      try {
        final AgentSpan span = scope.span();
        if (throwable != null) {
          span.addThrowable(throwable);
        }
        String lambdaRequestId = awsContext.getAwsRequestId();

        AgentTracer.get().notifyAppSecEnd(span, result);
        // Force the resource name back to the literal placeholder marker right
        // before finish so that the Datadog Lambda Extension's filter
        // (filter_span_from_lambda_library_or_runtime in
        // bottlecap/src/traces/trace_processor.rs, which compares
        // span.resource == "dd-tracer-serverless-span") drops the placeholder.
        // Other instrumentation (HTTP/JAX-RS) may have overwritten it with the
        // route ("POST /") during the invocation, in which case the extension
        // would fail to dedup, leading to the placeholder leaking to the backend
        // with parent_id=0 and detaching the inferred apigateway root from the
        // rest of the trace.
        // Use TAG_INTERCEPTOR priority because DDSpanContext.setResourceName
        // ignores writes whose priority is below the current resource priority,
        // and the HTTP/JAX-RS instrumentation will already have written
        // HTTP_FRAMEWORK_ROUTE (3) by this point.
        span.setResourceName(INVOCATION_SPAN_NAME, ResourceNamePriorities.TAG_INTERCEPTOR);
        span.finish();
        AgentTracer.get().notifyExtensionEnd(span, result, null != throwable, lambdaRequestId);
      } finally {
        scope.close();
      }
    }
  }
}
