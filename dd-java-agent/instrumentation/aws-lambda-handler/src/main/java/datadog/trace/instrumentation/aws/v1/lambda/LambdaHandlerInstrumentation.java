package datadog.trace.instrumentation.aws.v1.lambda;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.asm.Advice.Enter;
import static net.bytebuddy.asm.Advice.OnMethodEnter;
import static net.bytebuddy.asm.Advice.OnMethodExit;
import static net.bytebuddy.asm.Advice.Origin;
import static net.bytebuddy.asm.Advice.This;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class LambdaHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  private static final String HANDLER_ENV_NAME = "_HANDLER";
  private static final String HANDLER_SEPARATOR = "::";
  private static final String DEFAULT_METHOD_NAME = "handleRequest";
  private static final Logger log = LoggerFactory.getLogger(LambdaHandlerInstrumentation.class);

  private String instrumentedType;
  private String methodName;

  public LambdaHandlerInstrumentation() {
    super("aws-lambda");
    final String handler = System.getenv(HANDLER_ENV_NAME);
    if (null != handler) {
      final int split = handler.lastIndexOf(HANDLER_SEPARATOR);
      if (split == -1) {
        this.instrumentedType = handler;
        this.methodName = DEFAULT_METHOD_NAME;
      } else {
        this.instrumentedType = handler.substring(0, split);
        this.methodName = handler.substring(split + HANDLER_SEPARATOR.length());
      }
    }
  }

  @Override
  protected boolean defaultEnabled() {
    final String handler = System.getenv(HANDLER_ENV_NAME);
    return null != handler;
  }

  @Override
  public String instrumentedType() {
    return this.instrumentedType;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    if (null != this.instrumentedType && null != this.methodName) {
      // two args
      transformation.applyAdvice(
          isMethod()
              .and(named(this.methodName))
              .and(takesArgument(1, named("com.amazonaws.services.lambda.runtime.Context"))),
          getClass().getName() + "$ExtensionCommunicationAdvice");
      // three args (streaming)
      transformation.applyAdvice(
          isMethod()
              .and(named(this.methodName))
              .and(takesArgument(2, named("com.amazonaws.services.lambda.runtime.Context"))),
          getClass().getName() + "$ExtensionCommunicationAdvice");
      // full spec here : https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html
    }
  }

  protected String getMethodName() {
    return this.methodName;
  }

  public static class ExtensionCommunicationAdvice {
    @OnMethodEnter
    static AgentScope enter(
        @This final Object that,
        @Advice.Argument(0) final Object event,
        @Origin("#m") final String methodName) {
      AgentSpan.Context lambdaContext = AgentTracer.get().notifyExtensionStart(event);
      AgentSpan span =
          startSpan(UTF8BytesString.create("dd-tracer-serverless-span"), lambdaContext);
      final AgentScope scope = activateSpan(span);
      return scope;
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Origin String method,
        @Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      AgentTracer.get().notifyExtensionEnd(null != throwable);
      try {
        final AgentSpan span = scope.span();
        span.finish();
      } finally {
        scope.close();
      }
    }
  }
}
