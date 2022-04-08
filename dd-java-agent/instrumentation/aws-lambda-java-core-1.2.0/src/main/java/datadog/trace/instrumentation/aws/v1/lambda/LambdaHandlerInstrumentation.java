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

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.aws.v1.lambda.LambdaHandlerDecorator.DECORATE;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.ForwardedTagContext;

import net.bytebuddy.asm.Advice;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentracing.util.GlobalTracer;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format.Builtin;
import io.opentracing.propagation.TextMapAdapter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import datadog.trace.api.DDId;


@AutoService(Instrumenter.class)
public class LambdaHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
      System.out.println("instrumentedType=" + this.instrumentedType);
      System.out.println("methodName=" + this.methodName);
    }
  }

  @Override
  protected boolean defaultEnabled() {
    return true;
  }

  @Override
  public String instrumentedType() {
    return this.instrumentedType;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName + ".LambdaHandlerDecorator",
        packageName + ".LambdaSpanContext",
        "datadog.trace.core.propagation.ExtractedContext"
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
      System.out.println("[maxday-poc-java-no-code] - Enter the function");
      try {
        URL urlToOpen = new URL("http://127.0.0.1:8124/lambda/start-invocation");
        URLConnection con = urlToOpen.openConnection();
        con.setDoOutput(true);
        HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(args[0]);

        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        int length = out.length;
        http.setFixedLengthStreamingMode(length);
        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        http.connect();
        try (OutputStream os = http.getOutputStream()) {
          os.write(out);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream()));
        StringBuilder stringBuilder = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null)
        {
          stringBuilder.append(line + "\n");
        }
        String result = stringBuilder.toString();
        System.out.println(result);
        reader.close();
        System.out.println("[maxday-poc-java-no-code] - /start-invocation called");
      } catch (Exception e){
        System.out.println("ooopsy = " + e);
      }
      System.out.println("now checking the trace-context");
      try {
        URL urlToOpen = new URL("http://127.0.0.1:8124/trace-context");
        URLConnection con = urlToOpen.openConnection();
        con.setDoOutput(true);
        HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");
        String json = "{}";
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        int length = out.length;
        http.setFixedLengthStreamingMode(length);
        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        http.connect();
        try (OutputStream os = http.getOutputStream()) {
          os.write(out);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream()));
        StringBuilder stringBuilder = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null)
        {
          stringBuilder.append(line + "\n");
        }
        String result = stringBuilder.toString();
        System.out.println(result);
        reader.close();
        System.out.println("[maxday-poc-java-no-code] - /trace-context called");
        Map<String, List<String>> map = con.getHeaderFields();

        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
          System.out.println("Key = " + entry.getKey() + ", Value = ");
          for (String s : entry.getValue()) {
            System.out.println(s);
          }
        }

        System.out.println("[maxday-poc-java-no-code] - Creating a new LambdaSpanContext with traceID = " + map.get("X-Datadog-Trace-Id").get(0) + " and spanID = " + map.get("X-Datadog-Span-Id").get(0));
        //final AgentSpan.Context context = new LambdaSpanContext(map.get("X-Datadog-Trace-Id").get(0), map.get("X-Datadog-Span-Id").get(0));

        final ExtractedContext context = new ExtractedContext(
            DDId.from(map.get("X-Datadog-Trace-Id").get(0)),
            DDId.from(map.get("X-Datadog-Span-Id").get(0)),
            2,
            0,
            "superOrigin",
            0,
            null,
            null
        );

        final CharSequence LAMBDA_HANDLER = UTF8BytesString.create("aws.lambda");

        System.out.println(context.getSpanId());

        AgentSpan span = startSpan(LAMBDA_HANDLER, context);

        System.out.println("TRACE ID set in context =" + span.getTraceId());
        System.out.println("SPAN ID set in context =" + span.getSpanId());
        DECORATE.afterStart(span);
        DECORATE.onServiceExecution(span, that, methodName);

        final AgentScope scope = activateSpan(span);
        // Enable async propagation, so the newly spawned task will be associated back with this
        // original trace.
        scope.setAsyncPropagation(true);
        System.out.println("[maxday-poc-java-no-code] end of onStart, returning the scope");
        return scope;

      } catch (Exception e) {
        e.printStackTrace();
      }



      return null;
    }

    @OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(@Origin String method, @Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      System.out.println("[maxday-poc-java-no-code] - Exit the function" + method);
      try {
        URL urlToOpen = new URL("http://127.0.0.1:8124/lambda/end-invocation");
        URLConnection con = urlToOpen.openConnection();
        con.setDoOutput(true);
        HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");

        byte[] out ="{}".getBytes(StandardCharsets.UTF_8);
        int length = out.length;
        http.setFixedLengthStreamingMode(length);
        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (null != throwable) {
          http.setRequestProperty("x-datadog-invocation-error", "true");
        }
        http.connect();
        try (OutputStream os = http.getOutputStream()) {
          os.write(out);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream()));
        StringBuilder stringBuilder = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null)
        {
          stringBuilder.append(line + "\n");
        }
        String result = stringBuilder.toString();
        System.out.println(result);
        reader.close();
        System.out.println("[maxday-poc-java-no-code] - call to /end-invocation success");
      } catch (Exception e){
        System.out.println("ooopsy = " + e);
      }

      if (scope == null) {
        System.out.println("[maxday-poc-java-no-code] - scope is nil");
        return;
      }
      // If we have a scope (i.e. we were the top-level Twilio SDK invocation),
      try {
        final AgentSpan span = scope.span();

        if (throwable != null) {
          System.out.println("error detected");
          DECORATE.onError(span, throwable);
        }
        DECORATE.beforeFinish(span);
        span.finish();
        System.out.println("[maxday-poc-java-no-code] - span is finished");
      } finally {
        System.out.println("oupsy in close");
        scope.close();
        System.out.println("[maxday-poc-java-no-code] - span is closed");
      }

//      try {
//        URL urlToOpen = new URL("http://127.0.0.1:8124/lambda/flush");
//        URLConnection con = urlToOpen.openConnection();
//        con.setDoOutput(true);
//        HttpURLConnection http = (HttpURLConnection) con;
//        http.setRequestMethod("POST");
//
//        byte[] out ="{}".getBytes(StandardCharsets.UTF_8);
//        int length = out.length;
//        http.setFixedLengthStreamingMode(length);
//        http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
//        if (null != throwable) {
//          http.setRequestProperty("x-datadog-invocation-error", "true");
//        }
//        http.connect();
//        try (OutputStream os = http.getOutputStream()) {
//          os.write(out);
//        }
//        System.out.println("[maxday-poc-java-no-code] - call to /flush success");
//      } catch (Exception e){
//        System.out.println("ooopsy = " + e);
//      }
    }
  }
}
