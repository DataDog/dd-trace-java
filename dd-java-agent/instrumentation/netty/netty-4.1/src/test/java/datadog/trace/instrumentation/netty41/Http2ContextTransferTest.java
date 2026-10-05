package datadog.trace.instrumentation.netty41;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.Http2MultiplexHandlerStreamChannelInstrumentation.PropagateContextAdvice.afterCreate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.DefaultAttributeMap;
import org.junit.jupiter.api.Test;

class Http2ContextTransferTest {
  @Test
  void consumesParentContextOnceAndReusesItsAttribute() {
    Channel parent = channel(null);
    Channel first = channel(parent);
    Channel second = channel(parent);
    Attribute<Context> attribute = parent.attr(CONTEXT_ATTRIBUTE_KEY);
    Context context = Context.root();
    attribute.set(context);

    afterCreate(first);
    afterCreate(second);

    assertSame(context, first.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertNull(attribute.get());
    assertSame(attribute, parent.attr(CONTEXT_ATTRIBUTE_KEY));
    assertFalse(second.hasAttr(CONTEXT_ATTRIBUTE_KEY));
  }

  private static Channel channel(Channel parent) {
    // Avoid pipeline events creating attributes when another test installs Netty instrumentation.
    DefaultAttributeMap attributes = new DefaultAttributeMap();
    Channel channel = mock(Channel.class);
    when(channel.parent()).thenReturn(parent);
    when(channel.hasAttr(CONTEXT_ATTRIBUTE_KEY))
        .thenAnswer(ignored -> attributes.hasAttr(CONTEXT_ATTRIBUTE_KEY));
    when(channel.attr(CONTEXT_ATTRIBUTE_KEY))
        .thenAnswer(ignored -> attributes.attr(CONTEXT_ATTRIBUTE_KEY));
    return channel;
  }
}
