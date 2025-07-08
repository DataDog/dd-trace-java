package datadog.trace.instrumentation.armeria.jetty;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.linecorp.armeria.server.ServiceRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.server.HttpChannel;

@AutoService(InstrumenterModule.class)
public class ArmeriaHttpConnectionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ArmeriaHttpConnectionInstrumentation() {
    super("armeria-jetty", "armeria");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("com.linecorp.armeria.server.ServiceRequestContext");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AttributeKeys",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), getClass().getName() + "$JettyHttpChannelCaptureAdvice");
  }

  public static class JettyHttpChannelCaptureAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterInvoke(@Advice.This final HttpChannel channel) {
      final ServiceRequestContext current = ServiceRequestContext.currentOrNull();
      if (current != null) {
        current.setAttr(AttributeKeys.HTTP_CHANNEL_ATTRIBUTE_KEY, channel);
      }
    }
  }
}
