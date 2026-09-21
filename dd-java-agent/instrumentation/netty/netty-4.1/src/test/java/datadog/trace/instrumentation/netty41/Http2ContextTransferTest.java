package datadog.trace.instrumentation.netty41;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.Http2MultiplexHandlerStreamChannelInstrumentation.PropagateContextAdvice.afterCreate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.ContextKey;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.Attribute;
import org.junit.jupiter.api.Test;

class Http2ContextTransferTest {
  @Test
  void consumesParentContextOnceAndReusesItsAttribute() {
    EmbeddedChannel parent = new EmbeddedChannel();
    EmbeddedChannel first = new ChildChannel(parent);
    EmbeddedChannel second = new ChildChannel(parent);
    try {
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
    } finally {
      first.finishAndReleaseAll();
      second.finishAndReleaseAll();
      parent.finishAndReleaseAll();
    }
  }

  @Test
  void doesNotCreateChildAttributeForClearedParentContext() {
    EmbeddedChannel parent = new EmbeddedChannel();
    EmbeddedChannel child = new ChildChannel(parent);
    try {
      parent.attr(CONTEXT_ATTRIBUTE_KEY).set(null);

      afterCreate(child);

      assertFalse(child.hasAttr(CONTEXT_ATTRIBUTE_KEY));
    } finally {
      child.finishAndReleaseAll();
      parent.finishAndReleaseAll();
    }
  }

  @Test
  void preservesExistingChildContextAndDoesNotConsumeParent() {
    EmbeddedChannel parent = new EmbeddedChannel();
    EmbeddedChannel child = new ChildChannel(parent);
    try {
      Context parentContext = Context.root();
      Context childContext = Context.root().with(ContextKey.named("child"), "existing");
      parent.attr(CONTEXT_ATTRIBUTE_KEY).set(parentContext);
      child.attr(CONTEXT_ATTRIBUTE_KEY).set(childContext);

      afterCreate(child);

      assertSame(childContext, child.attr(CONTEXT_ATTRIBUTE_KEY).get());
      assertSame(parentContext, parent.attr(CONTEXT_ATTRIBUTE_KEY).get());
    } finally {
      child.finishAndReleaseAll();
      parent.finishAndReleaseAll();
    }
  }

  private static final class ChildChannel extends EmbeddedChannel {
    private final Channel parent;

    private ChildChannel(Channel parent) {
      this.parent = parent;
    }

    @Override
    public Channel parent() {
      return parent;
    }
  }
}
