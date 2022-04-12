package datadog.trace.instrumentation.http_url_connection;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.httpurlconnection.HeadersInjectAdapter.SETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlState;
import java.net.HttpURLConnection;
import java.util.Map;
import java.util.List;
import net.bytebuddy.asm.Advice;

import java.util.Arrays;

@AutoService(Instrumenter.class)
public class HttpUrlConnectionInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

  public HttpUrlConnectionInstrumentation() {
    super("httpurlconnection");
  }

  @Override
  public String[] knownMatchingTypes() {
    // we deliberately exclude various subclasses that are simple delegators
    return new String[] {
      "sun.net.www.protocol.http.HttpURLConnection", "java.net.HttpURLConnection"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.net.HttpURLConnection", HttpUrlState.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(namedOneOf("connect", "getOutputStream", "getInputStream")),
        HttpUrlConnectionInstrumentation.class.getName() + "$HttpUrlConnectionAdvice");
    transformation.applyAdvice(
        isMethod().and(isProtected()).and(named("plainConnect")),
        HttpUrlConnectionInstrumentation.class.getName() + "$HttpUrlConnectionAdvice");
  }

  public static class HttpUrlConnectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static HttpUrlState methodEnter(
        @Advice.This final HttpURLConnection thiz,
        @Advice.FieldValue("connected") final boolean connected) {

      System.out.println("here A");


      final String HTTP_TRACE_ENABLED_KEY = "x-datadog-tracing-enabled";

      boolean shouldTrace = true;
      try {
        Map<String,List<String>> map = thiz.getRequestProperties();
        shouldTrace = (
          null == map 
          || null == map.get(HTTP_TRACE_ENABLED_KEY)
          || null == map.get(HTTP_TRACE_ENABLED_KEY).get(0)
          || "false" != map.get(HTTP_TRACE_ENABLED_KEY).get(0)
        );
      } catch (IllegalStateException e) {
        shouldTrace = true;
      }
      
      if (!shouldTrace) {
        System.out.println("preventing tracing" + thiz.getURL());
        return null;
      } else {
        System.out.println("ok GO tracing" + thiz.getURL());
      }


      // XXX maxday STACK
      // System.out.println(Arrays.toString(Thread.currentThread().getStackTrace()).replace( ',', '\n' ));
      final ContextStore<HttpURLConnection, HttpUrlState> contextStore =
          InstrumentationContext.get(HttpURLConnection.class, HttpUrlState.class);
      final HttpUrlState state = contextStore.putIfAbsent(thiz, HttpUrlState.FACTORY);

      synchronized (state) {
        final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpURLConnection.class);
        System.out.println("callDepeth = " + callDepth);
        if (callDepth > 0) {
          return null;
        }
        System.out.println("state has span ? = " + state.hasSpan());
        System.out.println("state is finished ? = " + state.isFinished());
        System.out.println("state is connected ? = " + connected);
        if (!state.hasSpan() && !state.isFinished()) {
          final AgentSpan span = state.start(thiz);
          if (!connected) {
            propagate().inject(span, thiz, SETTER);
          }
        }
        return state;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final HttpUrlState state,
        @Advice.FieldValue("responseCode") final int responseCode,
        @Advice.Thrown final Throwable throwable,
        @Advice.Origin("#m") final String methodName) {

      if (state == null) {
        return;
      }

      synchronized (state) {
        if (state.hasSpan() && !state.isFinished()) {
          if (throwable != null) {
            state.finishSpan(responseCode, throwable);
          } else if ("getInputStream".equals(methodName)) {
            state.finishSpan(responseCode);
          }
        }
      }

      CallDepthThreadLocalMap.reset(HttpURLConnection.class);
    }
  }
}
