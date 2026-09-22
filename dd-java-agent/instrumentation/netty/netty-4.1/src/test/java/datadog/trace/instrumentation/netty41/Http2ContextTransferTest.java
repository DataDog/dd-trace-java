package datadog.trace.instrumentation.netty41;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.Http2MultiplexHandlerStreamChannelInstrumentation.PropagateContextAdvice.afterCreate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.context.ContextKey;
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
    Context context = Context.root().with(ContextKey.named("request"), "first");
    attribute.set(context);

    afterCreate(first);
    afterCreate(second);

    assertSame(context, first.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertNull(attribute.get());
    assertSame(attribute, parent.attr(CONTEXT_ATTRIBUTE_KEY));
    assertFalse(second.hasAttr(CONTEXT_ATTRIBUTE_KEY));

    Context nextContext = Context.root().with(ContextKey.named("request"), "second");
    attribute.set(nextContext);
    afterCreate(second);

    assertSame(nextContext, second.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertNull(attribute.get());
  }

  @Test
  void doesNotCreateChildAttributeForClearedParentContext() {
    Channel parent = channel(null);
    Channel child = channel(parent);
    parent.attr(CONTEXT_ATTRIBUTE_KEY).set(null);

    afterCreate(child);

    assertFalse(child.hasAttr(CONTEXT_ATTRIBUTE_KEY));
  }

  @Test
  void preservesExistingChildContextAndDoesNotConsumeParent() {
    Channel parent = channel(null);
    Channel child = channel(parent);
    Context parentContext = Context.root();
    Context childContext = Context.root().with(ContextKey.named("child"), "existing");
    parent.attr(CONTEXT_ATTRIBUTE_KEY).set(parentContext);
    child.attr(CONTEXT_ATTRIBUTE_KEY).set(childContext);

    afterCreate(child);

    assertSame(childContext, child.attr(CONTEXT_ATTRIBUTE_KEY).get());
    assertSame(parentContext, parent.attr(CONTEXT_ATTRIBUTE_KEY).get());
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
