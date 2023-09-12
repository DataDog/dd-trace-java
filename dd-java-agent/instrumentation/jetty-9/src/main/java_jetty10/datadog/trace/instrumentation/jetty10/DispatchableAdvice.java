package datadog.trace.instrumentation.jetty10;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.jetty.JettyBlockingHelper;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;

public class DispatchableAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
  public static boolean /* skip */ before(@Advice.FieldValue("this$0") HttpChannel channel) {
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return false;
    }
    Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
    if (rba == null) {
      return false;
    }

    boolean blocked =
        JettyBlockingHelper.block(channel.getRequest(), channel.getResponse(), rba, span);
    return blocked;
  }
}
