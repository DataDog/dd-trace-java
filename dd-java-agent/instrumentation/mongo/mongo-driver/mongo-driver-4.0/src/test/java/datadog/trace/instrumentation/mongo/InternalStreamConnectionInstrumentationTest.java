package datadog.trace.instrumentation.mongo;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.emptyList;
import static java.util.Comparator.comparingInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import com.mongodb.ServerAddress;
import com.mongodb.connection.AsyncCompletionHandler;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ServerId;
import com.mongodb.internal.async.SingleResultCallback;
import com.mongodb.internal.connection.InternalStreamConnection;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InternalStreamConnectionInstrumentationTest extends AbstractInstrumentationTest {

  @ParameterizedTest(
      name = "write completion retains parent after sendMessageAsync returns: failed={0}")
  @ValueSource(booleans = {false, true})
  void writeCompletionRetainsParentAfterSendMessageAsyncReturns(boolean failed) throws Exception {
    Field streamField = InternalStreamConnection.class.getDeclaredField("stream");
    streamField.setAccessible(true);
    AtomicReference<AsyncCompletionHandler<Void>> completion = new AtomicReference<>();
    Object stream =
        mock(
            streamField.getType(),
            invocation -> {
              if (invocation.getMethod().getName().equals("writeAsync")) {
                completion.set(invocation.getArgument(invocation.getArguments().length - 1));
                return null;
              }
              return RETURNS_DEFAULTS.answer(invocation);
            });
    InternalStreamConnection connection = newConnection();
    try {
      streamField.set(connection, stream);
      Method sendMessage =
          Arrays.stream(InternalStreamConnection.class.getMethods())
              .filter(method -> method.getName().equals("sendMessageAsync"))
              .findFirst()
              .get();
      Object operationContext =
          sendMessage.getParameterCount() == 4
              ? mock(sendMessage.getParameterTypes()[2], RETURNS_DEEP_STUBS)
              : null;
      AtomicReference<Throwable> callbackError = new AtomicReference<>();
      SingleResultCallback<Void> callback =
          (result, error) -> {
            callbackError.set(error);
            AgentSpan callbackSpan = startSpan("test", "callback");
            callbackSpan.finish();
          };

      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope scope = activateSpan(parent)) {
        Object[] args =
            sendMessage.getParameterCount() == 3
                ? new Object[] {emptyList(), 1, callback}
                : new Object[] {emptyList(), 1, operationContext, callback};
        sendMessage.invoke(connection, args);
      } finally {
        parent.finish();
      }
      // Complete the write only after the submitting scope has closed, without relying on thread
      // timing.
      assertNull(activeSpan());
      assertNotNull(completion.get());
      if (failed) {
        completion.get().failed(new IOException("failed write"));
      } else {
        completion.get().completed(null);
      }

      assertEquals(failed, callbackError.get() != null);
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent").resourceName("parent").tags(defaultTags()),
              span()
                  .childOfPrevious()
                  .operationName("callback")
                  .resourceName("callback")
                  .tags(defaultTags())));
    } finally {
      connection.close();
    }
  }

  private static InternalStreamConnection newConnection() throws Exception {
    // The driver's constructor and Stream package changed between the supported driver versions.
    Constructor<?> constructor =
        Arrays.stream(InternalStreamConnection.class.getConstructors())
            .min(comparingInt(Constructor::getParameterCount))
            .get();
    Class<?>[] parameterTypes = constructor.getParameterTypes();
    Object[] args = new Object[parameterTypes.length];
    for (int index = 0; index < parameterTypes.length; index++) {
      Class<?> type = parameterTypes[index];
      switch (type.getSimpleName()) {
        case "ServerId":
          args[index] = new ServerId(new ClusterId(), new ServerAddress());
          break;
        case "List":
          args[index] = emptyList();
          break;
        case "CommandListener":
          args[index] = null;
          break;
        case "ClusterConnectionMode":
          args[index] =
              Arrays.stream(type.getEnumConstants())
                  .filter(value -> ((Enum<?>) value).name().equals("SINGLE"))
                  .findFirst()
                  .get();
          break;
        default:
          args[index] = mock(type);
      }
    }
    return (InternalStreamConnection) constructor.newInstance(args);
  }
}
