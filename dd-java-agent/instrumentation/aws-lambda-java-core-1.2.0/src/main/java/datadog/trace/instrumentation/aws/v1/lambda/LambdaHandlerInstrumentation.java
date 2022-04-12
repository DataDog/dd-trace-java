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

//todo used?
import static datadog.trace.instrumentation.aws.v1.lambda.LambdaHandlerDecorator.DECORATE;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

import net.bytebuddy.asm.Advice;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import datadog.trace.bootstrap.instrumentation.api.DummyLambdaContext;

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
      
      System.out.println("[maxday-poc-java-no-code] - Enter the function");

       System.out.println("Starting invocation");
       try {
         URL urlToOpen = new URL("http://127.0.0.1:8124/lambda/start-invocation");
         URLConnection con = urlToOpen.openConnection();
         con.setDoOutput(true);
         HttpURLConnection http = (HttpURLConnection) con;
         http.setRequestMethod("POST");
         http.setRequestProperty("x-datadog-tracing-enabled", "false");
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
         

         DummyLambdaContext lambdaSpanContext = new DummyLambdaContext(con.getHeaderFields().get("X-Datadog-Trace-Id").get(0), con.getHeaderFields().get("X-Datadog-Span-Id").get(0));

         final CharSequence LAMBDA_HANDLER = UTF8BytesString.create("aws.lambda");

         System.out.println("TRACE ID set in lambdaSpanContext =" + lambdaSpanContext.getTraceId());
         System.out.println("SPAN ID set in lambdaSpanContext =" + lambdaSpanContext.getSpanId());

         //AgentSpan span = startSpan(LAMBDA_HANDLER, lambdaSpanContext);
         AgentSpan span = startSpan(LAMBDA_HANDLER, lambdaSpanContext);

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
         http.setRequestProperty("x-datadog-tracing-enabled", "false");

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
         //DECORATE.beforeFinish(span);
         span.finish();
         System.out.println("[maxday-poc-java-no-code] - span is finished");
       } finally {
         System.out.println("oupsy in close");
         scope.close();
         System.out.println("[maxday-poc-java-no-code] - span is closed");
       }

      try {
        URL urlToOpen = new URL("http://127.0.0.1:8124/lambda/flush");
        URLConnection con = urlToOpen.openConnection();
        con.setDoOutput(true);
        HttpURLConnection http = (HttpURLConnection) con;
        http.setRequestMethod("POST");
        http.setRequestProperty("x-datadog-tracing-enabled", "false");
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
        System.out.println("[maxday-poc-java-no-code] - call to /flush success");
      } catch (Exception e){
        System.out.println("ooopsy = " + e);
      }
    }
  }
}
