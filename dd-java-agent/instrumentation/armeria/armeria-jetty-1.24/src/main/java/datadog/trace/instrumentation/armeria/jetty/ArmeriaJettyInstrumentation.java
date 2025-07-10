package datadog.trace.instrumentation.armeria.jetty;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;

@AutoService(InstrumenterModule.class)
public class ArmeriaJettyInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ArmeriaJettyInstrumentation() {
    super("armeria-jetty", "armeria");
  }

  @Override
  public String instrumentedType() {
    return "com.linecorp.armeria.server.jetty.JettyService";
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
        isMethod()
            .and(named("serve"))
            .and(
                returns(named("com.linecorp.armeria.common.HttpResponse"))
                    .and(
                        takesArgument(
                            0, named("com.linecorp.armeria.server.ServiceRequestContext")))),
        getClass().getName() + "$JettySpanCloserAdvice");
  }

  public static class JettySpanCloserAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void afterInvoke(
        @Advice.Return final HttpResponse response,
        @Advice.Argument(0) ServiceRequestContext context) {
      // get the current attribute value and clear it if existing
      HttpChannel channel = context.setAttr(AttributeKeys.HTTP_CHANNEL_ATTRIBUTE_KEY, null);
      if (channel != null) {
        response.whenComplete().thenRun(channel::recycle);
      }
    }
  }
}
