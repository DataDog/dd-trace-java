package datadog.trace.instrumentation.netty41;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.netty.channel.Channel;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(InstrumenterModule.class)
public class Http2MultiplexHandlerStreamChannelInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public Http2MultiplexHandlerStreamChannelInstrumentation() {
    super("netty", "netty-4.1", "netty-4.1-http2");
  }

  @Override
  public String instrumentedType() {
    return "io.netty.handler.codec.http2.Http2MultiplexHandler$Http2MultiplexHandlerStreamChannel";
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
        ElementMatchers.isConstructor(), getClass().getName() + "$PropagateContextAdvice");
  }

  public static class PropagateContextAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterCreate(@Advice.This Channel self) {
      if (self.parent() != null
          && self.parent().hasAttr(AttributeKeys.CONTEXT_ATTRIBUTE_KEY)
          && !self.hasAttr(AttributeKeys.CONTEXT_ATTRIBUTE_KEY)) {
        self.attr(AttributeKeys.CONTEXT_ATTRIBUTE_KEY)
            .set(self.parent().attr(AttributeKeys.CONTEXT_ATTRIBUTE_KEY).getAndRemove());
      }
    }
  }
}
