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
import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class LambdaHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

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
    final String handler = System.getenv(HANDLER_ENV_NAME);
    return null != handler;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // two args
    transformation.applyAdvice(
        isMethod()
            .and(named("handleRequest"))
            .and(takesArgument(1, named("com.amazonaws.services.lambda.runtime.Context"))),
        getClass().getName() + "$ExtensionCommunicationAdvice");
    // three args (streaming)
    transformation.applyAdvice(
        isMethod()
            .and(named("handleRequest"))
            .and(takesArgument(2, named("com.amazonaws.services.lambda.runtime.Context"))),
        getClass().getName() + "$ExtensionCommunicationAdvice");
    // full spec here : https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html

  }

  public static class ExtensionCommunicationAdvice {
    @OnMethodEnter
    static AgentScope enter(
        @This final Object that,
        @Advice.Argument(0) final Object event,
        @Origin("#m") final String methodName) {

      if (CallDepthThreadLocalMap.incrementCallDepth(RequestHandler.class) > 0) {
        return null;
      }

      AgentSpan.Context lambdaContext = AgentTracer.get().notifyExtensionStart(event);
      final AgentSpan span;
      if (null == lambdaContext) {
        span = startSpan(INVOCATION_SPAN_NAME);
      } else {
        span = startSpan(INVOCATION_SPAN_NAME, lambdaContext);
      }
      final AgentScope scope = activateSpan(span);
      return scope;
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Origin String method,
        @Enter final AgentScope scope,
        @Advice.Return(typing = DYNAMIC) final Object result,
        @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }

      CallDepthThreadLocalMap.reset(RequestHandler.class);

      try {
        final AgentSpan span = scope.span();
        span.finish();
        AgentTracer.get().notifyExtensionEnd(span, result, null != throwable);
      } finally {
        scope.close();
      }
    }
  }
}
