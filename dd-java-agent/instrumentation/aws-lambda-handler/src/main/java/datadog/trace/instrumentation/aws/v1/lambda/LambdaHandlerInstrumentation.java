package datadog.trace.instrumentation.aws.v1.lambda;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.asm.Advice.AllArguments;
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
import datadog.trace.bootstrap.instrumentation.api.DummyLambdaContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.httpurlconnection.LambdaHandler;
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
      final String[] tokens = handler.split(HANDLER_SEPARATOR);
      if (tokens.length == 1) {
        this.instrumentedType = handler;
        this.methodName = DEFAULT_METHOD_NAME;
      } else if (tokens.length == 2) {
        this.instrumentedType = tokens[0];
        this.methodName = tokens[1];
      } else {
        log.error("wrong format for the handler, auto-instrumentation won't be applied");
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
  public String[] helperClassNames() {
    return new String[] {packageName + ".LambdaSpanContext"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    if (null != this.instrumentedType && null != this.methodName) {
      // one arg
      transformation.applyAdvice(
          isMethod()
              .and(named(this.methodName))
              .and(takesArgument(1, named("com.amazonaws.services.lambda.runtime.Context"))),
          getClass().getName() + "$ExtensionCommunicationAdvice");
      // two args
      transformation.applyAdvice(
          isMethod()
              .and(named(this.methodName))
              .and(takesArgument(2, named("com.amazonaws.services.lambda.runtime.Context"))),
          getClass().getName() + "$ExtensionCommunicationAdvice");
      // three args (streaming)
      transformation.applyAdvice(
          isMethod()
              .and(named(this.methodName))
              .and(takesArgument(3, named("com.amazonaws.services.lambda.runtime.Context"))),
          getClass().getName() + "$ExtensionCommunicationAdvice");
      // full spec here : https://docs.aws.amazon.com/lambda/latest/dg/java-handler.html
    }
  }

  public static class ExtensionCommunicationAdvice {
    @OnMethodEnter
    static AgentScope enter(
        @This final Object that,
        @AllArguments Object[] args,
        @Origin("#m") final String methodName) {
      Object lastArgs = (args.length > 0) ? args[args.length - 1] : null;
      DummyLambdaContext lambdaSpanContext = LambdaHandler.notifyStartInvocation(lastArgs);
      if (null == lambdaSpanContext) {
        return null;
      }
      AgentSpan span = startSpan(UTF8BytesString.create("aws.lambda"), lambdaSpanContext);
      final AgentScope scope = activateSpan(span);
      return scope;
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Origin String method,
        @Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      LambdaHandler.notifyEndInvocation(null != throwable);
      if (scope == null) {
        return;
      }
      try {
        final AgentSpan span = scope.span();
        span.finish();
      } finally {
        scope.close();
      }
    }
  }
}
