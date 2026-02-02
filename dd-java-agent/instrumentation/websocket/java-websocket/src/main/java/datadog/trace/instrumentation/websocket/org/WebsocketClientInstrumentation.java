package datadog.trace.instrumentation.websocket.org;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.websocket.org.WebSocketClientDecorator.CLIENT_DECORATE;
import static datadog.trace.instrumentation.websocket.org.WebSocketDecorator.WEBSOCKET_CLOSE;
import static datadog.trace.instrumentation.websocket.org.WebSocketDecorator.WEBSOCKET_OPEN;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.handshake.ServerHandshake;

public class WebsocketClientInstrumentation implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public WebsocketClientInstrumentation() {
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.java_websocket.client.WebSocketClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onWebsocketOpen"))
            .and(takesArguments(2))
        ,
        getClass().getName() + "$OnOpenAdvice");

    transformer.applyAdvice(
        isConstructor().and(takesArguments(4)),
        getClass().getName() + "$ClientConstructorAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("connect"))
            .and(takesArguments(0))
        ,
        getClass().getName() + "$ConnectAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onWebsocketMessage"))
            .and(takesArguments(2))
        ,
        getClass().getName() + "$OnMessageAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("onWebsocketClose"))
            .and(takesArguments(4))
        ,
        getClass().getName() + "$OnCloseAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("onWebsocketError"))
            .and(takesArguments(2))
        ,
        getClass().getName() + "$ErrorAdvice");


  }

  public static class ConnectAdvice{
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This WebSocketClient client) {

      // 防止嵌套调用（如果 connect 内部调用了重载方法）
      int callDepth = CallDepthThreadLocalMap.incrementCallDepth(WebSocketClient.class);
      if (callDepth > 0) {
        return null;
      }
      // 创建握手 Span
      AgentScope scope = CLIENT_DECORATE.startHandshakeSpan(client);
      InstrumentationContext.get(WebSocketClient.class, AgentSpan.class).put(client, scope.span());
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter AgentScope scope,
        @Advice.Thrown Throwable throwable) {

      CallDepthThreadLocalMap.decrementCallDepth(WebSocketClient.class);
      if (scope == null) {
        return;
      }
      // // 如果 connect() 抛出异常（同步失败），立即结束 span
      if (throwable != null) {
        AgentSpan span = scope.span();
        CLIENT_DECORATE.onError(scope.span(), throwable);
        CLIENT_DECORATE.beforeFinish(scope.span());

        scope.close();
        scope.span().finish();
      }
      System.out.println("exit...............");
      // 不要在这里关闭 scope，因为握手是异步的，onOpen 时才结束
      // scope.close() 应该在 onOpen 或 onError 中调用
    }
  }

  public static class ClientConstructorAdvice{
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(value = 1,readOnly = false) Draft protocolDraft) {
      if(protocolDraft==null){
        return;
      }
      if (protocolDraft instanceof Draft_6455 && !(protocolDraft instanceof TraceDraft_6455)){
        try {
          // 创建 tracing wrapper
          protocolDraft = TraceDraft_6455.fromDraft6455((Draft_6455) protocolDraft);
        } catch (Exception e) {
          // 如果包装失败，继续使用原始 draft（fail-safe）
        }
      }
    }
  }

  public static class OnOpenAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) WebSocket conn,
        @Advice.Argument(value = 1) Handshakedata handshake, @Advice.This WebSocketClient client) {
      // 从 context 中获取之前创建的 span
      AgentSpan span = InstrumentationContext.get(WebSocketClient.class, AgentSpan.class).get(client);
      System.out.println("open...............");
      if (span == null) {
        return noopScope();
      }
      span.setOperationName(WEBSOCKET_OPEN);
      CLIENT_DECORATE.onHandshakeSuccess(span, ((ServerHandshake)handshake).getHttpStatus());
      // 清理 context
      InstrumentationContext.get(WebSocketClient.class, AgentSpan.class).remove(client);
      WebsocketAgentSpanContext spanContext = new WebsocketAgentSpanContext(span.context(),span.getTag(Tags.SPAN_KIND));
      // defaultPropagator().inject(span, headers, SETTER);
      InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).put(conn,spanContext);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      CLIENT_DECORATE.onError(scope.span(), throwable);
      CLIENT_DECORATE.beforeFinish(scope.span());

      scope.close();
      scope.span().finish();
    }
  }

  public static class OnMessageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) WebSocket engine,
        @Advice.Argument(value = 1) Object message ) {
      System.out.println("message...............");
      WebsocketAgentSpanContext context = InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).get(engine);
      if (context == null) {
        return activateSpan(noopSpan());
      }
      final AgentSpan wsSpan =
          CLIENT_DECORATE.onMessage(message,context.getSpanContext());
      return activateSpan(wsSpan);
    }

      @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
      public static void onExit(
          @Advice.Enter final AgentScope scope,
          @Advice.Thrown final Throwable throwable) {
        if (scope == null) {
          return;
        }
        CLIENT_DECORATE.onError(scope.span(), throwable);
        CLIENT_DECORATE.beforeFinish(scope.span());

        scope.close();
        scope.span().finish();
      }
    }

  public static class OnCloseAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This WebSocketClient client,
        @Advice.Argument(value = 0) WebSocket conn,
        @Advice.Argument(value = 1) int code,
        @Advice.Argument(value = 2) String message,
        @Advice.Argument(value = 3) boolean remote) {
      System.out.println("close...............");
      WebsocketAgentSpanContext context = InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).get(conn);
      if (context == null) {
        AgentSpan agentSpan = InstrumentationContext.get(WebSocketClient.class, AgentSpan.class).get(client);
        if (agentSpan != null){
          agentSpan.setOperationName(WEBSOCKET_CLOSE);
          InstrumentationContext.get(WebSocketClient.class, AgentSpan.class).remove(client);
          return activateSpan(agentSpan);
        }
        return activateSpan(noopSpan());
      }

      final AgentSpan wsSpan =
          CLIENT_DECORATE.onClose(code,message,remote,context.getSpanContext());
      InstrumentationContext.get(WebSocket.class, WebsocketAgentSpanContext.class).remove(conn);
      return activateSpan(wsSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      CLIENT_DECORATE.onError(scope.span(), throwable);
      CLIENT_DECORATE.beforeFinish(scope.span());
      scope.close();
      scope.span().finish();
    }
  }


  public static class ErrorAdvice {
    @Advice.OnMethodExit
    public static void onExit(
        @Advice.This WebSocketClient client,
        @Advice.Argument(value = 0) WebSocket conn,
        @Advice.Argument(value = 1) Exception ex) {
        AgentSpan agentSpan = InstrumentationContext.get(WebSocketClient.class, AgentSpan.class).get(client);
        if (agentSpan == null){
          return;
        }
      System.out.println("onerror...............");
      CLIENT_DECORATE.onError(agentSpan, ex);
      // InstrumentationContext.get(WebSocketClient.class, AgentSpan.class).put(client,agentSpan);
    }
  }

}
