package datadog.trace.instrumentation.mongo;

import static datadog.trace.api.DDTags.ERROR_MSG;
import static datadog.trace.api.DDTags.ERROR_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import datadog.context.Context;
import datadog.context.ContextHandle;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.bson.BsonDocument;
import org.bson.BsonReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@WithConfig(key = "legacy.context-manager.enabled", value = "false")
class MongoPendingSpanTest extends DDJavaSpecification {
  private ContextHandle previousContext;
  private AgentTracer.TracerAPI previousTracer;
  private CoreTracer tracer;
  private DDSpan parent;
  private ListWriter writer;

  @BeforeEach
  void setUpContext() {
    previousTracer = AgentTracer.get();
    writer = new ListWriter();
    tracer = CoreTracer.builder().writer(writer).build();
    AgentTracer.forceRegister(tracer);
    AgentTracer.installLegacyContextManager();
    previousContext = Context.current().checkpoint();
    parent = (DDSpan) tracer.startSpan("test", "parent");
    parent.swap();
    Context.setCaptureEnabled(false);
  }

  @AfterEach
  void restoreContext() {
    MongoPendingSpan.take();
    previousContext.swap();
    parent.finish();
    tracer.close();
    AgentTracer.forceRegister(previousTracer);
  }

  @Test
  void pendingSpanDoesNotChangeContextOrCaptureFlags() {
    Context caller = Context.current();
    DDSpan span = (DDSpan) tracer.startSpan("test", "command");
    MongoPendingSpan.enter(span);

    assertSame(caller, Context.current());
    assertFalse(Context.isCaptureEnabled());
    assertSame(span, MongoPendingSpan.take());
    assertNull(MongoPendingSpan.take());
    MongoPendingSpan.onExit(span, null);
    assertSame(caller, Context.current());
    assertFalse(Context.isCaptureEnabled());
    assertFalse(span.isFinished());
    span.finish();
  }

  @Test
  void failureBeforeCommandStartedFinishesTheUnclaimedSpan() throws Exception {
    assertFailureBeforeCommandStarted();
  }

  @Test
  @WithConfig(key = "legacy.context-manager.enabled", value = "true")
  void legacyFailureBeforeCommandStartedFinishesTheUnclaimedSpan() throws Exception {
    assertFailureBeforeCommandStarted();
  }

  private void assertFailureBeforeCommandStarted() throws Exception {
    Context caller = Context.current();
    DDSpan span = (DDSpan) tracer.startSpan("java-mongo", MongoDecorator.OPERATION_NAME);
    MongoPendingSpan.enter(span);
    RuntimeException failure = new RuntimeException("command failed before listener");

    MongoPendingSpan.onExit(span, failure);
    MongoPendingSpan.onExit(span, failure);
    assertSame(caller, Context.current());
    assertFalse(Context.isCaptureEnabled());
    assertTrue(span.isFinished());
    assertTrue(span.isError());
    assertEquals(failure.getMessage(), span.getTag(ERROR_MSG));
    assertEquals(failure.getClass().getName(), span.getTag(ERROR_TYPE));
    assertEquals(parent.getSpanId(), span.getParentId());
    assertNull(MongoPendingSpan.take());
    parent.finish();
    writer.waitForTraces(1);
    assertEquals(2, writer.get(0).size());
    assertTrue(writer.get(0).contains(span));
  }

  @Test
  void completedAdviceDoesNotConsumeASubsequentPendingSpan() {
    DDSpan first = (DDSpan) tracer.startSpan("test", "first");
    MongoPendingSpan.enter(first);
    assertSame(first, MongoPendingSpan.take());
    DDSpan second = (DDSpan) tracer.startSpan("test", "second");
    MongoPendingSpan.enter(second);

    MongoPendingSpan.onExit(first, new IllegalStateException("first command failed"));
    assertSame(second, MongoPendingSpan.take());
    MongoPendingSpan.onExit(second, null);
    assertFalse(first.isFinished());
    assertFalse(first.isError());
    assertFalse(second.isFinished());
    first.finish();
    second.finish();
  }

  @Test
  void skippedAdviceDoesNotConsumeThePendingSpan() {
    DDSpan span = (DDSpan) tracer.startSpan("test", "command");
    MongoPendingSpan.enter(span);
    MongoPendingSpan.onExit(null, new IllegalStateException("nested advice failed"));
    assertSame(span, MongoPendingSpan.take());
    assertFalse(span.isFinished());
    span.finish();
  }

  @Test
  void commandListenerClaimsAndFinishesThePendingSpan() {
    assertListenerHandoff();
  }

  @Test
  @WithConfig(key = "legacy.context-manager.enabled", value = "true")
  void legacyCommandListenerClaimsAndFinishesThePendingSpan() {
    assertListenerHandoff();
  }

  private void assertListenerHandoff() {
    Context caller = Context.current();
    DDSpan span = (DDSpan) tracer.startSpan("java-mongo", MongoDecorator.OPERATION_NAME);
    MongoPendingSpan.enter(span);
    MongoCommandListener listener = listener();

    listener.commandStarted(startedEvent());
    MongoPendingSpan.onExit(span, null);
    assertNull(MongoPendingSpan.take());
    assertSame(caller, Context.current());
    assertFalse(Context.isCaptureEnabled());
    assertFalse(span.isFinished());

    listener.commandSucceeded(succeededEvent());
    assertTrue(span.isFinished());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void commandListenerWithoutPendingSpanCreatesAChildOfTheCaller(boolean sameOperationName)
      throws Exception {
    if (sameOperationName) {
      parent.setOperationName(MongoDecorator.OPERATION_NAME);
    }
    MongoCommandListener listener = listener();
    listener.commandStarted(startedEvent());
    assertSame(parent, AgentTracer.activeSpan());
    assertFalse(Context.isCaptureEnabled());
    listener.commandSucceeded(succeededEvent());
    assertFalse(parent.isFinished());
    parent.finish();
    writer.waitForTraces(1);
    assertEquals(2, writer.get(0).size());
    assertTrue(
        writer.get(0).stream()
            .anyMatch(span ->
                span.getOperationName().equals(MongoDecorator.OPERATION_NAME)
                    && span.getParentId() == parent.getSpanId()));
  }

  private static MongoCommandListener listener() {
    BsonScrubber scrubber =
        new BsonScrubber() {
          @Override
          public void pipe(BsonReader reader) {}

          @Override
          public void close() {}

          @Override
          public String getResourceName() {
            return "{}";
          }
        };
    return new MongoCommandListener(
        1,
        new MongoDecorator() {
          @Override
          protected BsonScrubber newScrubber() {
            return scrubber;
          }
        });
  }

  private static CommandStartedEvent startedEvent() {
    return new CommandStartedEvent(1, null, "database", "find", new BsonDocument());
  }

  private static CommandSucceededEvent succeededEvent() {
    return new CommandSucceededEvent(1, null, "find", new BsonDocument(), 1L);
  }
}
