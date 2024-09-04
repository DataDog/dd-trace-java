package datadog.trace.instrumentation.mule4;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.InstrumentationContext;
import net.bytebuddy.asm.Advice;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.core.privileged.event.PrivilegedEvent;

public class PrivilegedEventSetCurrentAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(@Advice.Argument(0) final PrivilegedEvent event) {
    CurrentEventHelper.handleEventChange(
        event, InstrumentationContext.get(EventContext.class, Pair.class));
  }
}
