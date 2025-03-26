package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import javax.websocket.MessageHandler;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

public class MessageHandlerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public MessageHandlerInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String hierarchyMarkerType() {
    return namespace + ".websocket.MessageHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("onMessage"))
            .and(
                takesArguments(1) // whole
                    .or(takesArguments(2).and(takesArgument(1, boolean.class)))), // partial
        getClass().getName() + "$OnMessageAdvice");
  }

  public static class OnMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final MessageHandler handler,
        @Advice.Argument(value = 0, typing = Assigner.Typing.DYNAMIC) final Object data,
        @Advice.Argument(value = 1, optional = true) final Boolean last,
        @Advice.Local("handlerContext") HandlerContext.Receiver handlerContext) {
      handlerContext =
          InstrumentationContext.get(MessageHandler.class, HandlerContext.Receiver.class)
              .get(handler);
      if (handlerContext == null) {
        return null;
      }
      if (CallDepthThreadLocalMap.incrementCallDepth(MessageHandler.class) > 0) {
        return null;
      }

      final AgentSpan wsSpan =
          DECORATE.onReceiveFrameStart(handlerContext, data, last != null && last);
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("handlerContext") HandlerContext.Receiver handlerContext,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(value = 1, optional = true) final Boolean last) {
      if (scope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(MessageHandler.class);
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
}
