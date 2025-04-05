package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.netty41.client.HttpClientRequestTracingHandler;
import datadog.trace.instrumentation.netty41.client.HttpClientResponseTracingHandler;
import datadog.trace.instrumentation.netty41.client.HttpClientTracingHandler;
import datadog.trace.instrumentation.netty41.server.HttpServerRequestTracingHandler;
import datadog.trace.instrumentation.netty41.server.HttpServerResponseTracingHandler;
import datadog.trace.instrumentation.netty41.server.HttpServerTracingHandler;
import datadog.trace.instrumentation.netty41.server.MaybeBlockResponseHandler;
import datadog.trace.instrumentation.netty41.server.websocket.WebSocketServerTracingHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.Attribute;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class NettyChannelPipelineInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  static final String INSTRUMENTATION_NAME = "netty";
  static final String[] ADDITIONAL_INSTRUMENTATION_NAMES = {"netty-4.1"};

  public NettyChannelPipelineInstrumentation() {
    super(INSTRUMENTATION_NAME, ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.netty.channel.ChannelPipeline";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AttributeKeys",
      // client helpers
      packageName + ".client.NettyHttpClientDecorator",
      packageName + ".client.NettyResponseInjectAdapter",
      packageName + ".client.HttpClientRequestTracingHandler",
      packageName + ".client.HttpClientResponseTracingHandler",
      packageName + ".client.HttpClientTracingHandler",
      // server helpers
      packageName + ".server.ResponseExtractAdapter",
      packageName + ".server.NettyHttpServerDecorator",
      packageName + ".server.NettyHttpServerDecorator$NettyBlockResponseFunction",
      packageName + ".server.BlockingResponseHandler",
      packageName + ".server.BlockingResponseHandler$IgnoreAllWritesHandler",
      packageName + ".server.HttpServerRequestTracingHandler",
      packageName + ".server.HttpServerResponseTracingHandler",
      packageName + ".server.HttpServerTracingHandler",
      packageName + ".server.MaybeBlockResponseHandler",
      packageName + ".server.websocket.WebSocketServerTracingHandler",
      packageName + ".server.websocket.WebSocketServerResponseTracingHandler",
      packageName + ".server.websocket.WebSocketServerRequestTracingHandler",
      packageName + ".NettyHttp2Helper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("addFirst", "addLast"))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandler"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$AddHandlerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("addBefore", "addAfter"))
            .and(takesArgument(3, named("io.netty.channel.ChannelHandler"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$AddHandlerAdvice");
    transformer.applyAdvice(
        isMethod().and(named("connect")).and(returns(named("io.netty.channel.ChannelFuture"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$ConnectAdvice");
  }

  /**
   * When certain handlers are added to the pipeline, we want to add our corresponding tracing
   * handlers. If those handlers are later removed, we may want to remove our handlers. That is not
   * currently implemented.
   */
  public static class AddHandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static int checkDepth(
        @Advice.Argument(value = 2, optional = true) final Object handler2,
        @Advice.Argument(value = 3, optional = true) final ChannelHandler handler3) {
      ChannelHandler handler =
          handler2 instanceof ChannelHandler ? (ChannelHandler) handler2 : handler3;
      /**
       * Previously we used one unique call depth tracker for all handlers, using
       * ChannelPipeline.class as a key. The problem with this approach is that it does not work
       * with netty's io.netty.channel.ChannelInitializer which provides an `initChannel` that can
       * be used to `addLast` other handlers. In that case the depth would exceed 0 and handlers
       * added from initializers would not be considered. Using the specific handler key instead of
       * the generic ChannelPipeline.class will help us both to handle such cases and avoid adding
       * our additional handlers in case of internal calls of `addLast` to other method overloads
       * with a compatible signature.
       */
      return CallDepthThreadLocalMap.incrementCallDepth(handler.getClass());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(
        @Advice.Enter final int depth,
        @Advice.This final ChannelPipeline pipeline,
        @Advice.Argument(value = 2, optional = true) final Object handler2,
        @Advice.Argument(value = 3, optional = true) final ChannelHandler handler3) {
      if (depth > 0) {
        return;
      }

      ChannelHandler handler =
          handler2 instanceof ChannelHandler ? (ChannelHandler) handler2 : handler3;

      try {
        // Server pipeline handlers
        if (handler instanceof HttpServerCodec) {
          addHandlerAfter(pipeline,
              handler,
              new HttpServerTracingHandler(),
              MaybeBlockResponseHandler.INSTANCE);
        } else if (handler instanceof HttpRequestDecoder) {
          addHandlerAfter(pipeline,
              handler,
              HttpServerRequestTracingHandler.INSTANCE);
        } else if (handler instanceof HttpResponseEncoder) {
          addHandlerAfter(pipeline,
              handler,
              HttpServerResponseTracingHandler.INSTANCE,
              MaybeBlockResponseHandler.INSTANCE);
        }
        else if (handler instanceof WebSocketServerProtocolHandler) {
          addHandlerAfter(pipeline,
              "HttpServerTracingHandler#0",
              new WebSocketServerTracingHandler());
        }
        // Client pipeline handlers
        else if (handler instanceof HttpClientCodec) {
          addHandlerAfter(pipeline,
              handler,
              new HttpClientTracingHandler());
        } else if (handler instanceof HttpRequestEncoder) {
          addHandlerAfter(pipeline,
              handler,
              HttpClientRequestTracingHandler.INSTANCE);
        } else if (handler instanceof HttpResponseDecoder) {
          addHandlerAfter(pipeline,
              handler,
              HttpClientResponseTracingHandler.INSTANCE);
        } else if (NettyHttp2Helper.isHttp2FrameCodec(handler)) {
          if (NettyHttp2Helper.isServer(handler)) {
            addHandlerAfter(pipeline,
                handler,
                new HttpServerTracingHandler(),
                MaybeBlockResponseHandler.INSTANCE);
          } else {
            addHandlerAfter(pipeline, handler, new HttpClientTracingHandler());
          }
        }
      } catch (final IllegalArgumentException e) {
        // Prevented adding duplicate handlers.
      } finally {
        CallDepthThreadLocalMap.reset(handler.getClass());
      }
    }
  }

  public static class ConnectAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void addParentSpan(@Advice.This final ChannelPipeline pipeline) {
      AgentScope.Continuation continuation = captureActiveSpan();
      if (continuation != noopContinuation()) {
        final Attribute<AgentScope.Continuation> attribute =
            pipeline.channel().attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY);
        if (!attribute.compareAndSet(null, continuation)) {
          continuation.cancel();
        }
      }
    }
  }

  public static void addHandlerAfter(
      final ChannelPipeline pipeline,
      final String name,
      final ChannelHandler... toAdd) {
    String handlerName = name;
    for (ChannelHandler handler : toAdd) {
      ChannelHandler existing = pipeline.get(handler.getClass());
      if (existing != null) {
        pipeline.remove(existing);
      }
      pipeline.addAfter(handlerName, null, handler);
      ChannelHandlerContext handlerContext = pipeline.context(handler);
      if (handlerContext != null) {
        handlerName = handlerContext.name();
      }
    }
  }

  public static void addHandlerAfter(
      final ChannelPipeline pipeline,
      final ChannelHandler handler,
      final ChannelHandler... toAdd) {
    ChannelHandlerContext handlerContext = pipeline.context(handler);
    if (handlerContext != null) {
      String handlerName = handlerContext.name();
      addHandlerAfter(pipeline, handlerName, toAdd);
    }
  }

}
