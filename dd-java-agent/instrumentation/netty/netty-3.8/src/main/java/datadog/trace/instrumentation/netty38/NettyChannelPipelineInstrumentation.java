package datadog.trace.instrumentation.netty38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;

@AutoService(InstrumenterModule.class)
public class NettyChannelPipelineInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  static final String INSTRUMENTATION_NAME = "netty";
  static final String[] ADDITIONAL_INSTRUMENTATION_NAMES = {"netty-3.8"};

  public NettyChannelPipelineInstrumentation() {
    super(INSTRUMENTATION_NAME, ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.jboss.netty.channel.ChannelPipeline";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AbstractNettyAdvice",
      packageName + ".ChannelTraceContext",
      packageName + ".ChannelTraceContext$Factory",
      packageName + ".ChannelPipelineAdviceUtil",
      // Util
      packageName + ".util.CombinedSimpleChannelHandler",
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
      packageName + ".server.NettyHttpServerDecorator$IgnoreBlockingExceptionHandler",
      packageName + ".server.BlockingResponseHandler",
      packageName + ".server.BlockAllWritesHandler",
      packageName + ".server.HttpServerRequestTracingHandler",
      packageName + ".server.HttpServerResponseTracingHandler",
      packageName + ".server.HttpServerTracingHandler",
      packageName + ".server.MaybeBlockResponseHandler",
      packageName + ".server.websocket.WebSocketServerTracingHandler",
      packageName + ".server.websocket.WebSocketServerRequestTracingHandler",
      packageName + ".server.websocket.WebSocketServerResponseTracingHandler",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(nameStartsWith("add"))
            .and(takesArgument(1, named("org.jboss.netty.channel.ChannelHandler"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$ChannelPipelineAdd2ArgsAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(nameStartsWith("add"))
            .and(takesArgument(2, named("org.jboss.netty.channel.ChannelHandler"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$ChannelPipelineAdd3ArgsAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.jboss.netty.channel.Channel", packageName + ".ChannelTraceContext");
  }

  public static class ChannelPipelineAdd2ArgsAdvice extends AbstractNettyAdvice {
    @Advice.OnMethodEnter
    public static int checkDepth(
        @Advice.This final ChannelPipeline pipeline,
        @Advice.Argument(1) final ChannelHandler handler) {
      // Pipelines are created once as a factory and then copied multiple times using the same add
      // methods as we are hooking. If our handler has already been added we need to remove it so we
      // don't end up with duplicates (this throws an exception)
      if (pipeline.get(handler.getClass().getName()) != null) {
        pipeline.remove(handler.getClass().getName());
      }
      return CallDepthThreadLocalMap.incrementCallDepth(ChannelPipeline.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(
        @Advice.Enter final int depth,
        @Advice.This final ChannelPipeline pipeline,
        @Advice.Argument(1) final ChannelHandler handler) {
      if (depth > 0) {
        return;
      }

      final ContextStore<Channel, ChannelTraceContext> contextStore =
          InstrumentationContext.get(Channel.class, ChannelTraceContext.class);

      ChannelPipelineAdviceUtil.wrapHandler(contextStore, pipeline, handler);
    }
  }

  public static class ChannelPipelineAdd3ArgsAdvice extends AbstractNettyAdvice {
    @Advice.OnMethodEnter
    public static int checkDepth(
        @Advice.This final ChannelPipeline pipeline,
        @Advice.Argument(2) final ChannelHandler handler) {
      // Pipelines are created once as a factory and then copied multiple times using the same add
      // methods as we are hooking. If our handler has already been added we need to remove it so we
      // don't end up with duplicates (this throws an exception)
      if (pipeline.get(handler.getClass().getName()) != null) {
        pipeline.remove(handler.getClass().getName());
      }
      return CallDepthThreadLocalMap.incrementCallDepth(ChannelPipeline.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(
        @Advice.Enter final int depth,
        @Advice.This final ChannelPipeline pipeline,
        @Advice.Argument(2) final ChannelHandler handler) {
      if (depth > 0) {
        return;
      }

      final ContextStore<Channel, ChannelTraceContext> contextStore =
          InstrumentationContext.get(Channel.class, ChannelTraceContext.class);

      ChannelPipelineAdviceUtil.wrapHandler(contextStore, pipeline, handler);
    }
  }
}
