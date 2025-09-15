package datadog.trace.instrumentation.jetty10;

import datadog.context.Context;
import datadog.trace.instrumentation.jetty.JettyBlockingHelper;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;

public class DispatchableAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
  public static boolean /* skip */ before(@Advice.FieldValue("this$0") HttpChannel channel) {
    return JettyBlockingHelper.block(
        channel.getRequest(), channel.getResponse(), Context.current());
  }
}
