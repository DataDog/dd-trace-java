package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;

@AutoService(Instrumenter.class)
public final class HttpAsyncClientExchangeHandlerInstrumentation extends Instrumenter.Tracing {

  public HttpAsyncClientExchangeHandlerInstrumentation() {
    super("httpasyncclient", "apache-httpasyncclient");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler");
  }

  @Override
  public ElementMatcher<? super TypeDescription> shortCutMatcher() {
    return NameMatchers.<TypeDescription>namedOneOf(
            "org.apache.http.impl.nio.client.DefaultClientExchangeHandlerImpl",
            "org.apache.http.impl.nio.client.PipeliningClientExchangeHandlerImpl",
            "org.apache.http.impl.nio.client.MinimalClientExchangeHandlerImpl")
        .and(declaresField(named("resultFuture")));
  }

  @Override
  public ElementMatcher<? super TypeDescription> hierarchyMatcher() {
    // must ensure the field is declared (if it is no longer declared, we miss a profiler event,
    // but tracing is unaffected
    return ElementMatchers.<TypeDescription>declaresField(named("resultFuture"))
        .and(
            implementsInterface(
                named("org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "org.apache.http.concurrent.BasicFuture", "org.apache.http.concurrent.FutureCallback");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".DelegatingRequestProducer",
      packageName + ".TraceContinuedFutureCallback",
      packageName + ".ApacheHttpAsyncClientDecorator"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("consumeContent", "produceContent"), getClass().getName() + "$RecordActivity");
  }

  public static final class RecordActivity {
    // executed when content is being consumed or produced
    @Advice.OnMethodEnter
    public static TraceContinuedFutureCallback<?> before(
        @Advice.FieldValue("resultFuture") BasicFuture<?> resultFuture) {
      FutureCallback<?> callback =
          InstrumentationContext.get(BasicFuture.class, FutureCallback.class).get(resultFuture);
      if (callback instanceof TraceContinuedFutureCallback) {
        TraceContinuedFutureCallback<?> tracedCallback = (TraceContinuedFutureCallback<?>) callback;
        tracedCallback.resume();
        return tracedCallback;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceContinuedFutureCallback<?> callback) {
      if (null != callback) {
        callback.suspend();
      }
    }
  }
}
