package datadog.trace.instrumentation.armeria.jetty;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("onCompleted").and(takesNoArguments()),
        getClass().getName() + "$JettySpanCloserAdvice");
  }

  public static class JettySpanCloserAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterInvoke(@Advice.This HttpChannel channel) {
      EndPoint endPoint = channel.getEndPoint();
      if (endPoint != null && ARMERIA_END_POINT.equals(endPoint.getClass().getName())) {
        // Armeria creates a channel per request and its transport does not recycle it.
        // Jetty's recycle advice finishes the servlet span.
        channel.recycle();
      }
    }
  }
}
