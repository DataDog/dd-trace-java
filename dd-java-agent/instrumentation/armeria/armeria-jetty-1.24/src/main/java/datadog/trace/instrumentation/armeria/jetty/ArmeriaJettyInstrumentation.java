package datadog.trace.instrumentation.armeria.jetty;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.HttpChannel;

@AutoService(InstrumenterModule.class)
public class ArmeriaJettyInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private static final String ARMERIA_END_POINT =
      "com.linecorp.armeria.server.jetty.ArmeriaEndPoint";

  public ArmeriaJettyInstrumentation() {
    super("armeria-jetty", "armeria");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed(ARMERIA_END_POINT);
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(instrumentedType(), Boolean.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("onCompleted").and(takesNoArguments()),
        getClass().getName() + "$JettySpanCloserAdvice");
    transformer.applyAdvice(
        named("handle").and(takesNoArguments()),
        getClass().getName() + "$RecycleAfterHandleAdvice");
  }

  public static class JettySpanCloserAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterInvoke(
        @Advice.This HttpChannel channel, @Advice.Thrown Throwable error) {
      EndPoint endPoint = channel.getEndPoint();
      if (endPoint != null && ARMERIA_END_POINT.equals(endPoint.getClass().getName())) {
        // Armeria creates a channel per request and its transport does not recycle it.
        // Jetty's recycle advice finishes the servlet span.
        if (error == null) {
          channel.recycle();
        } else {
          // Keep the request state available until Jetty has handled the error.
          InstrumentationContext.get(HttpChannel.class, Boolean.class).put(channel, Boolean.TRUE);
        }
      }
    }
  }

  public static class RecycleAfterHandleAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterInvoke(@Advice.This HttpChannel channel) {
      ContextStore<HttpChannel, Boolean> recycleAfterHandle =
          InstrumentationContext.get(HttpChannel.class, Boolean.class);
      if (recycleAfterHandle.remove(channel) == Boolean.TRUE) {
        channel.recycle();
      }
    }
  }
}
