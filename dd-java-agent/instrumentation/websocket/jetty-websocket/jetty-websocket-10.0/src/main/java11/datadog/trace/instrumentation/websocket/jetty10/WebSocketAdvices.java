package datadog.trace.instrumentation.websocket.jetty10;

import datadog.trace.bootstrap.ExceptionLogger;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import javax.websocket.Endpoint;
import javax.websocket.Session;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketMessageMetadata;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;

@SuppressFBWarnings("UC_USELESS_OBJECT_STACK")
public class WebSocketAdvices {
  public static class OpenClose9Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 2, readOnly = false) MethodHandle openHandle,
        @Advice.Argument(value = 3, readOnly = false) MethodHandle closeHandle,
        @Advice.Argument(value = 1) final Object origin) {

      // we skip wrapping the method handle in case the origin is already an Enpoint since this will
      // be already handled by the jsr356 instrumentation
      if (origin == null || origin instanceof Endpoint) {
        return;
      }
      // we need then to wrap those two method handles jetty is calling when the websocket is opened
      // and closed since jetty is directly calling them.
      // We also insert other arguments at the beginning in order to provide data we need. Inserting
      // at the beginning won't break bind and invoke jetty will do.
      openHandle =
          MethodHandles.insertArguments(
              MethodHandleWrappers.OPEN_METHOD_HANDLE,
              0,
              openHandle,
              InstrumentationContext.get(Session.class, HandlerContext.Sender.class),
              InstrumentationContext.get(JavaxWebSocketSession.class, Boolean.class));
      closeHandle =
          MethodHandles.insertArguments(
              MethodHandleWrappers.CLOSE_METHOD_HANDLE,
              0,
              closeHandle,
              InstrumentationContext.get(Session.class, HandlerContext.Sender.class));
    }
  }

  public static class OpenClose10Advice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 3, readOnly = false) MethodHandle openHandle,
        @Advice.Argument(value = 4, readOnly = false) MethodHandle closeHandle,
        @Advice.Argument(value = 2) final Object origin) {

      // we skip wrapping the method handle in case the origin is already an Enpoint since this will
      // be already handled by the jsr356 instrumentation
      if (origin == null || origin instanceof Endpoint) {
        return;
      }
      // we need then to wrap those two method handles jetty is calling when the websocket is opened
      // and closed since jetty is directly calling them.
      // We also insert other arguments at the beginning in order to provide data we need. Inserting
      // at the beginning won't break bind and invoke jetty will do.
      openHandle =
          MethodHandles.insertArguments(
              MethodHandleWrappers.OPEN_METHOD_HANDLE,
              0,
              openHandle,
              InstrumentationContext.get(Session.class, HandlerContext.Sender.class),
              InstrumentationContext.get(JavaxWebSocketSession.class, Boolean.class));
      closeHandle =
          MethodHandles.insertArguments(
              MethodHandleWrappers.CLOSE_METHOD_HANDLE,
              0,
              closeHandle,
              InstrumentationContext.get(Session.class, HandlerContext.Sender.class));
    }
  }

  public static class MessageSinkAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final JavaxWebSocketSession session,
        @Advice.Argument(1) final JavaxWebSocketMessageMetadata metadata) {
      if (metadata == null || metadata.getMethodHandle() == null) {
        return;
      }
      final AgentSpan current = AgentTracer.get().activeSpan();

      if (current == null) {
        return;
      }
      try {
        // we need to manipulate this method handle after jetty already bound template variables and
        // arguments otherwise the call will mismatch.
        // we insert arguments at the beginning to inject our own data and make the method as vararg
        // to collect arguments since we need to call the original and we cannot know which one will
        // be provided. In fact, it will depend on the data used (i.e. byte[], String, etc...) and
        // if partial delivery is handled (so it will also accept a boolean for the fin bit signal).
        metadata.setMethodHandle(
            MethodHandles.insertArguments(
                    MethodHandleWrappers.MESSAGE_METHOD_HANDLE,
                    0,
                    metadata.getMethodHandle(),
                    session,
                    new HandlerContext.Receiver(current, session.getId()),
                    InstrumentationContext.get(JavaxWebSocketSession.class, Boolean.class))
                .asVarargsCollector(Object[].class));
      } catch (Throwable t) {
        // log it
        ExceptionLogger.LOGGER.debug("Error while mutating MessageSink ", t);
      }
    }
  }
}
