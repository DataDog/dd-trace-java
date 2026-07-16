package datadog.trace.instrumentation.netty41.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.context.Context;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class MaybeBlockResponseHandlerTest {

  @Test
  void dropsWritesAfterBlockedContextHasBeenRemoved() {
    EmbeddedChannel channel = new EmbeddedChannel(MaybeBlockResponseHandler.INSTANCE);
    ServerRequestContext serverContext = ServerRequestContext.add(channel, Context.root(), null);
    ServerRequestContext.markResponseBlocked(channel);
    ServerRequestContext.remove(channel, serverContext);
    ByteBuf lateResponseChunk = Unpooled.buffer().writeByte(1);

    assertFalse(channel.writeOutbound(lateResponseChunk));

    assertEquals(0, lateResponseChunk.refCnt());
    assertNull(channel.readOutbound());
    channel.finishAndReleaseAll();
  }
}
