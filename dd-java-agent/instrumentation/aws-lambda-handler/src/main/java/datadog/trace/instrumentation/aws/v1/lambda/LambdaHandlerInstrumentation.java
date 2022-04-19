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
import datadog.trace.bootstrap.instrumentation.api.DummyLambdaContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.LambdaHandler;
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
    return new String[] {
      "com.squareup.moshi.JsonDataException",
      "com.squareup.moshi.StandardJsonAdapters",
      "com.squareup.moshi.JsonAdapter",
      "com.squareup.moshi.internal.NonNullJsonAdapter",
      "com.squareup.moshi.internal.NullSafeJsonAdapter",
      "com.squareup.moshi.JsonReader",
      "com.squareup.moshi.JsonWriter",
      "com.squareup.moshi.JsonValueWriter",
      "com.squareup.moshi.JsonValueReader",
      "com.squareup.moshi.JsonAdapter$1",
      "com.squareup.moshi.JsonAdapter$2",
      "com.squareup.moshi.JsonAdapter$3",
      "com.squareup.moshi.JsonAdapter$4",
      "com.squareup.moshi.JsonAdapter$Factory",
      "com.squareup.moshi.CollectionJsonAdapter",
      "com.squareup.moshi.CollectionJsonAdapter$1",
      "com.squareup.moshi.CollectionJsonAdapter$2",
      "com.squareup.moshi.CollectionJsonAdapter$3",
      "com.squareup.moshi.MapJsonAdapter",
      "com.squareup.moshi.MapJsonAdapter$1",
      "com.squareup.moshi.ArrayJsonAdapter",
      "com.squareup.moshi.ArrayJsonAdapter$1",
      "com.squareup.moshi.ClassJsonAdapter",
      "com.squareup.moshi.ClassJsonAdapter$1",
      "com.squareup.moshi.Types",
      "com.squareup.moshi.StandardJsonAdapters$1",
      "com.squareup.moshi.StandardJsonAdapters$2",
      "com.squareup.moshi.StandardJsonAdapters$3",
      "com.squareup.moshi.StandardJsonAdapters$4",
      "com.squareup.moshi.StandardJsonAdapters$5",
      "com.squareup.moshi.StandardJsonAdapters$6",
      "com.squareup.moshi.StandardJsonAdapters$7",
      "com.squareup.moshi.StandardJsonAdapters$8",
      "com.squareup.moshi.StandardJsonAdapters$9",
      "com.squareup.moshi.StandardJsonAdapters$10",
      "com.squareup.moshi.StandardJsonAdapters$11",
      "com.squareup.moshi.internal.Util",
      "com.squareup.moshi.Moshi",
      "com.squareup.moshi.Moshi$LookupChain",
      "com.squareup.moshi.Moshi$Lookup",
      "com.squareup.moshi.JsonClass",
      "com.squareup.moshi.ClassFactory",
      "com.squareup.moshi.ClassFactory$1",
      "com.squareup.moshi.ClassFactory$2",
      "com.squareup.moshi.ClassFactory$3",
      "com.squareup.moshi.ClassFactory$4",
      "com.squareup.moshi.Json",
      "com.squareup.moshi.JsonReader$Options",
      "com.squareup.moshi.JsonUtf8Writer",
      "com.squareup.moshi.StandardJsonAdapters$ObjectJsonAdapter",
      "com.squareup.moshi.ClassJsonAdapter$FieldBinding",
      "com.squareup.moshi.internal.Util$ParameterizedTypeImpl",
      "com.squareup.moshi.Moshi$Builder",
      "com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent",
      "datadog.trace.agent.core.LambdaHandler"
    };
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

  public static class ExtensionCommunicationAdvice {
    @OnMethodEnter
    static AgentScope enter(
        @This final Object that,
        @Advice.Argument(0) final Object event,
        @Origin("#m") final String methodName) {
      DummyLambdaContext lambdaSpanContext = LambdaHandler.notifyStartInvocation(event);
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
