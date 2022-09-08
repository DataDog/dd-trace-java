package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.concurrent.BasicFuture;
import org.apache.http.concurrent.FutureCallback;

@AutoService(Instrumenter.class)
public class HttpAsyncClientExchangeHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.WithTypeStructure {

  public HttpAsyncClientExchangeHandlerInstrumentation() {
    super("httpasyncclient", "apache-httpasyncclient");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.http.impl.nio.client.DefaultClientExchangeHandlerImpl",
      "org.apache.http.impl.nio.client.PipeliningClientExchangeHandlerImpl",
      "org.apache.http.impl.nio.client.MinimalClientExchangeHandlerImpl"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.http.nio.protocol.HttpAsyncClientExchangeHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public ElementMatcher<? extends ByteCodeElement> structureMatcher() {
    // must ensure the field is declared (if it is no longer declared
    // we miss a profiler event, but tracing is unaffected)
    return declaresField(named("resultFuture"));
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
