package datadog.trace.instrumentation.jersey2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.RequestContextSlot;
import net.bytebuddy.asm.Advice;
import org.glassfish.jersey.server.internal.routing.UriRoutingContext;

@AutoService(InstrumenterModule.class)
public class UriRoutingContextGetPathSegmentsInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public UriRoutingContextGetPathSegmentsInstrumentation() {
    super("jersey");
  }

  @Override
  public String muzzleDirective() {
    return "jersey_server_2";
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.internal.routing.UriRoutingContext";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getPathSegments").and(takesArguments(1)).and(takesArgument(0, boolean.class)),
        getClass().getName() + "$GetPathSegmentsAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetPathSegmentsAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.This UriRoutingContext thiz,
        @Advice.Argument(0) boolean decode,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }

      try {
        // rely on instr for getPathParameters. We do lose matrix variables though
        thiz.getPathParameters(decode);
      } catch (Throwable it) {
        t = it;
      }
    }
  }
}
