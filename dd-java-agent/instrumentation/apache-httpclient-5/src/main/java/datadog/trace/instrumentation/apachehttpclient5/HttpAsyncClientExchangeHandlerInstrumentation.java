package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.hc.core5.concurrent.FutureCallback;

@AutoService(Instrumenter.class)
public class HttpAsyncClientExchangeHandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.WithTypeStructure {

  public HttpAsyncClientExchangeHandlerInstrumentation() {
    super("httpasyncclient5", "apache-httpasyncclient5");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"org.apache.hc.core5.http.nio.support.BasicClientExchangeHandler"};
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.hc.core5.http.nio.AsyncClientExchangeHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public ElementMatcher<? extends ByteCodeElement> structureMatcher() {
    // must ensure the field is declared (if it is no longer declared
    // we miss a profiler event, but tracing is unaffected)
    return declaresField(named("resultCallback"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ApacheHttpClientDecorator",
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".DelegatingRequestChannel",
      packageName + ".DelegatingRequestProducer",
      packageName + ".TraceContinuedFutureCallback"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("consume", "produce"), getClass().getName() + "$RecordActivity");
  }

  public static final class RecordActivity {
    // executed when content is being consumed or produced
    @Advice.OnMethodEnter
    public static TraceContinuedFutureCallback<?> before(
        @Advice.FieldValue("resultCallback") FutureCallback<?> callback) {
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
