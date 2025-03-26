package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.BYTE_BUFFER_SIZE_CALCULATOR;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.CHAR_SEQUENCE_SIZE_CALCULATOR;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

public class AsyncRemoteEndpointInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public AsyncRemoteEndpointInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String hierarchyMarkerType() {
    return namespace + ".websocket.RemoteEndpoint$Async";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("sendText"))
            .and(
                takesArguments(1)
                    .or(
                        takesArguments(2)
                            .and(takesArgument(1, named(namespace + ".websocket.SendHandler")))))
            .and(takesArgument(0, named("java.lang.String"))),
        getClass().getName() + "$SendTextAdvice");
    transformer.applyAdvice(
        isPublic()
            .and(named("sendBinary"))
            .and(
                takesArguments(1)
                    .or(
                        takesArguments(2)
                            .and(takesArgument(1, named(namespace + ".websocket.SendHandler")))))
            .and(takesArgument(0, named("java.nio.ByteBuffer"))),
        getClass().getName() + "$SendBinaryAdvice");
    transformer.applyAdvice(
        isPublic()
            .and(named("sendObject"))
            .and(
                takesArguments(1)
                    .or(
                        takesArguments(2)
                            .and(takesArgument(1, named(namespace + ".websocket.SendHandler"))))),
        getClass().getName() + "$SendObjectAdvice");
  }

  public static class SendTextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final RemoteEndpoint.Async self,
        @Advice.Argument(0) String text,
        @Advice.Argument(
                value = 1,
                optional = true,
                readOnly = false,
                typing = Assigner.Typing.DYNAMIC)
            SendHandler sendHandler,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      handlerContext =
          InstrumentationContext.get(RemoteEndpoint.class, HandlerContext.Sender.class).get(self);
      if (handlerContext == null
          || CallDepthThreadLocalMap.incrementCallDepth(RemoteEndpoint.class) > 0) {
        return null;
      }

      final AgentSpan wsSpan =
          DECORATE.onSendFrameStart(
              handlerContext,
              CHAR_SEQUENCE_SIZE_CALCULATOR.getFormat(),
              CHAR_SEQUENCE_SIZE_CALCULATOR.getLengthFunction().applyAsInt(text));
      if (sendHandler != null) {
        sendHandler = new TracingSendHandler(sendHandler, handlerContext);
      }
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Future<Void> future) {
      CallDepthThreadLocalMap.decrementCallDepth(RemoteEndpoint.class);
      if (scope == null) {
        return;
      }
      try {
        if (throwable != null) {
          DECORATE.onError(scope, throwable);
          DECORATE.onFrameEnd(handlerContext);
        } else if (future != null) {
          // FIXME: Knowing when the future really completes would imply instrumenting all the
          // possible implementations.
          // In this case we will just finish the span to have a trace of this send even if the
          // duration is not exact
          DECORATE.onFrameEnd(handlerContext);
        }
      } finally {
        scope.close();
      }
    }
  }

  public static class SendBinaryAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final RemoteEndpoint.Async self,
        @Advice.Argument(0) ByteBuffer buffer,
        @Advice.Argument(
                value = 1,
                optional = true,
                readOnly = false,
                typing = Assigner.Typing.DYNAMIC)
            SendHandler sendHandler,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      handlerContext =
          InstrumentationContext.get(RemoteEndpoint.class, HandlerContext.Sender.class).get(self);
      if (handlerContext == null
          || CallDepthThreadLocalMap.incrementCallDepth(RemoteEndpoint.class) > 0) {
        return null;
      }

      final AgentSpan wsSpan =
          DECORATE.onSendFrameStart(
              handlerContext,
              BYTE_BUFFER_SIZE_CALCULATOR.getFormat(),
              BYTE_BUFFER_SIZE_CALCULATOR.getLengthFunction().applyAsInt(buffer));
      if (sendHandler != null) {
        sendHandler = new TracingSendHandler(sendHandler, handlerContext);
      }
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Future<Void> future) {
      CallDepthThreadLocalMap.decrementCallDepth(RemoteEndpoint.class);
      if (scope == null) {
        return;
      }
      try {
        if (throwable != null) {
          DECORATE.onError(scope, throwable);
          DECORATE.onFrameEnd(handlerContext);
        } else if (future != null) {
          // FIXME: Knowing when the future really completes would imply instrumenting all the
          // possible implementations.
          // In this case we will just finish the span to have a trace of this send even if the
          // duration is not exact
          DECORATE.onFrameEnd(handlerContext);
        }
      } finally {
        scope.close();
      }
    }
  }

  public static class SendObjectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final RemoteEndpoint.Async self,
        @Advice.Argument(
                value = 1,
                optional = true,
                readOnly = false,
                typing = Assigner.Typing.DYNAMIC)
            SendHandler sendHandler,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      handlerContext =
          InstrumentationContext.get(RemoteEndpoint.class, HandlerContext.Sender.class).get(self);
      if (handlerContext == null
          || CallDepthThreadLocalMap.incrementCallDepth(RemoteEndpoint.class) > 0) {
        return null;
      }

      final AgentSpan wsSpan =
          DECORATE.onSendFrameStart(handlerContext, BYTE_BUFFER_SIZE_CALCULATOR.getFormat(), 0);
      if (sendHandler != null) {
        sendHandler = new TracingSendHandler(sendHandler, handlerContext);
      }
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Future<Void> future) {
      CallDepthThreadLocalMap.decrementCallDepth(RemoteEndpoint.class);
      if (scope == null) {
        return;
      }
      try {
        if (throwable != null) {
          DECORATE.onError(scope, throwable);
          DECORATE.onFrameEnd(handlerContext);
        } else if (future != null) {
          // FIXME: Knowing when the future really completes would imply instrumenting all the
          // possible implementations.
          // In this case we will just finish the span to have a trace of this send even if the
          // duration is not exact
          DECORATE.onFrameEnd(handlerContext);
        }
      } finally {
        scope.close();
      }
    }
  }
}
