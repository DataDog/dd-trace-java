package datadog.trace.instrumentation.aws.v1.lambda;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.asm.Advice.AllArguments;
import static net.bytebuddy.asm.Advice.This;
import static net.bytebuddy.asm.Advice.Enter;
import static net.bytebuddy.asm.Advice.OnMethodEnter;
import static net.bytebuddy.asm.Advice.OnMethodExit;
import static net.bytebuddy.asm.Advice.Thrown;
import static net.bytebuddy.asm.Advice.Origin;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.httpurlconnection.LambdaHandler;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

import net.bytebuddy.asm.Advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.Versioned;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import datadog.trace.bootstrap.instrumentation.api.DummyLambdaContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(Instrumenter.class)
public class LambdaHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  private static final Logger log = LoggerFactory.getLogger(LambdaHandlerInstrumentation.class);
  private static final CharSequence AWS_LAMBDA = UTF8BytesString.create("aws.lambda")
  private String instrumentedType;
  private String methodName;

  public LambdaHandlerInstrumentation() {
    super("aws-lambda");
    final String handler = System.getenv("_HANDLER");
    if (null != handler) {
      final String[] tokens = handler.split("::");
      if (tokens.length == 1) {
        this.instrumentedType = handler;
        this.methodName = "handleRequest";
      } else if (tokens.length == 2) {
        this.instrumentedType = tokens[0];
        this.methodName = tokens[1];
      }
    }
  }

  @Override
  protected boolean defaultEnabled() {
    final String handler = System.getenv("_HANDLER");
    return null != handler;
  }

  @Override
  public String instrumentedType() {
    return this.instrumentedType;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".LambdaSpanContext"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    if (null != this.instrumentedType && null != this.methodName) {
      transformation.applyAdvice(
          isMethod().and(named(this.methodName)),
          getClass().getName() + "$ExtensionCommunicationAdvice");
    }
  }


  public static class ExtensionCommunicationAdvice {
    @OnMethodEnter
    static AgentScope enter(@This final Object that, @AllArguments Object[] args, @Origin("#m") final String methodName) {
      log.debug("Entering the lamba handler");
      DummyLambdaContext lambdaSpanContext = LambdaHandler.notifyStartInvocation(args[0]);
      if (null == lambdaSpanContext) {
        return null;
      }
      AgentSpan span = startSpan(AWS_LAMBDA, lambdaSpanContext);
      final AgentScope scope = activateSpan(span);
      return scope;
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@Origin String method, @Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      log.debug("Exiting the lamba handler");
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
