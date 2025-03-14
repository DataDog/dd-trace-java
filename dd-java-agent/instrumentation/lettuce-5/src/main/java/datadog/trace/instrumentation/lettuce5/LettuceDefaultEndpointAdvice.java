package datadog.trace.instrumentation.lettuce5;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.netty.channel.Channel;
import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

public class LettuceDefaultEndpointAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentSpan onEnter(
      @Advice.This Object obj,@Advice.FieldValue("channel") Channel channel
  ) {
    if (channel == null) {
      return activeSpan();
    }
    AgentSpan span = activeSpan();

    SocketAddress socketAddress = channel.remoteAddress();
    if (socketAddress instanceof InetSocketAddress) {
      InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
//      String peer = inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort();
      if (span!=null){
//        span.setTag("peer",peer);
        span.setTag(Tags.PEER_HOSTNAME,inetSocketAddress.getHostName());
        span.setTag(Tags.PEER_PORT,inetSocketAddress.getPort());
      }
    }
    return activeSpan();
  }
}
