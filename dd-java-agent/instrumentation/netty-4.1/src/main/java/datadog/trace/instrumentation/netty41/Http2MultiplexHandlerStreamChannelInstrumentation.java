package datadog.trace.instrumentation.netty41;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.netty.channel.Channel;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class Http2MultiplexHandlerStreamChannelInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
          && self.parent().hasAttr(AttributeKeys.SPAN_ATTRIBUTE_KEY)
          && !self.hasAttr(AttributeKeys.SPAN_ATTRIBUTE_KEY)) {
        self.attr(AttributeKeys.SPAN_ATTRIBUTE_KEY)
            .set(self.parent().attr(AttributeKeys.SPAN_ATTRIBUTE_KEY).getAndRemove());
      }
    }
  }
}
