package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.BYTE_BUFFER_SIZE_CALCULATOR;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.CHAR_SEQUENCE_SIZE_CALCULATOR;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import javax.websocket.RemoteEndpoint;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class BasicRemoteEndpointInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public BasicRemoteEndpointInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String hierarchyMarkerType() {
    return namespace + ".websocket.RemoteEndpoint$Basic";
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
            .and(takesArguments(1).or(takesArguments(2).and(takesArgument(1, boolean.class))))
            .and(takesArgument(0, named("java.lang.String")))
            .and(returns(void.class)),
        getClass().getName() + "$SendTextAdvice");
    transformer.applyAdvice(
        isPublic()
            .and(named("sendBinary"))
            .and(takesArguments(1).or(takesArguments(2).and(takesArgument(1, boolean.class))))
            .and(takesArgument(0, named("java.nio.ByteBuffer")))
            .and(returns(void.class)),
        getClass().getName() + "$SendBinaryAdvice");
    transformer.applyAdvice(
        isPublic().and(named("sendObject")).and(takesArguments(1)).and(returns(void.class)),
        getClass().getName() + "$SendObjectAdvice");
    transformer.applyAdvice(
        isPublic().and(named("getSendStream")).and(takesNoArguments()),
        getClass().getName() + "$WrapStreamAdvice");
    transformer.applyAdvice(
        isPublic().and(named("getSendWriter")).and(takesNoArguments()),
        getClass().getName() + "$WrapWriterAdvice");
  }

  public static class SendTextAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope before(
        @Advice.This final RemoteEndpoint.Basic self,
        @Advice.Argument(0) String text,
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
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(value = 1, optional = true) final Boolean last) {
      CallDepthThreadLocalMap.decrementCallDepth(RemoteEndpoint.class);

      if (scope == null) {
        return;
      }
      try {
        boolean finishSpan = last == null || last;
        if (throwable != null) {
          finishSpan = true;
          DECORATE.onError(scope, throwable);
        }
        if (finishSpan) {
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
        @Advice.This final RemoteEndpoint.Basic self,
        @Advice.Argument(0) ByteBuffer buffer,
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
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(value = 1, optional = true) final Boolean last) {
      CallDepthThreadLocalMap.decrementCallDepth(RemoteEndpoint.class);
      if (scope == null) {
        return;
      }
      try {
        boolean finishSpan = last == null || last;
        if (throwable != null) {
          finishSpan = true;
          DECORATE.onError(scope, throwable);
        }
        if (finishSpan) {
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
        @Advice.This final RemoteEndpoint.Basic self,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext) {
      handlerContext =
          InstrumentationContext.get(RemoteEndpoint.class, HandlerContext.Sender.class).get(self);
      if (handlerContext == null
          || CallDepthThreadLocalMap.incrementCallDepth(RemoteEndpoint.class) > 0) {
        return null;
      }

      // we actually cannot know the size and the type since this the conversion is done by
      // encoders/decoders.
      // we can anyway instrument also the Encoders but that would add much more complexity.
      // right now this is not in scope
      final AgentSpan wsSpan = DECORATE.onSendFrameStart(handlerContext, null, 0);
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("handlerContext") HandlerContext.Sender handlerContext,
        @Advice.Thrown final Throwable throwable) {
      CallDepthThreadLocalMap.decrementCallDepth(RemoteEndpoint.class);
      if (scope == null) {
        return;
      }
      try {
        if (throwable != null) {
          DECORATE.onError(scope, throwable);
        }
        DECORATE.onFrameEnd(handlerContext);
      } finally {
        scope.close();
      }
    }
  }

  public static class WrapWriterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.This final RemoteEndpoint.Basic self,
        @Advice.Return(readOnly = false) Writer writer) {
      if (writer instanceof TracingWriter) {
        return;
      }
      final HandlerContext.Sender handlerContext =
          InstrumentationContext.get(RemoteEndpoint.class, HandlerContext.Sender.class).get(self);
      if (handlerContext != null) {
        writer = new TracingWriter(writer, handlerContext);
      }
    }
  }

  public static class WrapStreamAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.This final RemoteEndpoint.Basic self,
        @Advice.Return(readOnly = false) OutputStream outputStream) {
      if (outputStream instanceof TracingOutputStream) {
        return;
      }
      final HandlerContext.Sender handlerContext =
          InstrumentationContext.get(RemoteEndpoint.class, HandlerContext.Sender.class).get(self);
      if (handlerContext != null) {
        outputStream = new TracingOutputStream(outputStream, handlerContext);
      }
    }
  }
}
