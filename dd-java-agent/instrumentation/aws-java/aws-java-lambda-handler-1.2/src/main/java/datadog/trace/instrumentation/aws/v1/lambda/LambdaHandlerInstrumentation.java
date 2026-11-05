package datadog.trace.instrumentation.aws.v1.lambda;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.asm.Advice.Enter;
import static net.bytebuddy.asm.Advice.OnMethodEnter;
import static net.bytebuddy.asm.Advice.OnMethodExit;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
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
        getClass().getName() + "$InvocationAdvice");
  }

  public static class InvocationAdvice {
    @OnMethodEnter(suppress = Throwable.class)
    static ContextScope enter(
        @Advice.Argument(0) final Object in,
        @Advice.Argument(2) final Context awsContext,
        @Advice.Local("nativeInvocation") AgentTracer.LambdaInvocation nativeInvocation) {

      if (CallDepthThreadLocalMap.incrementCallDepth(RequestHandler.class) > 0) {
        return null;
      }
      try {
        nativeInvocation =
            AgentTracer.get()
                .startLambdaInvocation(
                    in,
                    awsContext.getAwsRequestId(),
                    awsContext.getFunctionName(),
                    awsContext.getFunctionVersion(),
                    awsContext.getInvokedFunctionArn());
        if (nativeInvocation != null) {
          return nativeInvocation.activate();
        }
      } catch (Throwable failure) {
        try {
          if (nativeInvocation != null) {
            try {
              nativeInvocation.onExit(null, null);
            } finally {
              nativeInvocation.finish();
            }
          }
        } finally {
          CallDepthThreadLocalMap.reset(RequestHandler.class);
        }
      }
      CallDepthThreadLocalMap.reset(RequestHandler.class);
      return null;
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Enter final ContextScope scope,
        @Advice.Argument(1) final Object result,
        @Advice.Thrown final Throwable throwable,
        @Advice.Local("nativeInvocation") final AgentTracer.LambdaInvocation nativeInvocation) {

      if (scope == null) {
        return;
      }

      CallDepthThreadLocalMap.reset(RequestHandler.class);

      try {
        nativeInvocation.onExit(result, throwable);
      } finally {
        try {
          scope.close();
        } finally {
          nativeInvocation.finish();
        }
      }
    }
  }
}
