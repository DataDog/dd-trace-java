package datadog.trace.instrumentation.netty41;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;

import datadog.context.ContextContinuation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

public final class Http2ConnectContinuationListener implements ChannelFutureListener {
  public static final ChannelFutureListener INSTANCE = new Http2ConnectContinuationListener();

  private Http2ConnectContinuationListener() {}

  @Override
  public void operationComplete(final ChannelFuture future) {
    if (future.isSuccess() || future.isCancelled()) {
      cancel(future.channel());
    }
    // Failed connects are left for ChannelFutureListenerInstrumentation, which creates the
    // netty.connect error span under this continuation.
  }

  public static void cancel(final Channel channel) {
    if (channel == null) {
      return;
    }
    final ContextContinuation continuation =
        channel.attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY).getAndRemove();
    if (continuation != null) {
      continuation.release();
    }
  }
}
