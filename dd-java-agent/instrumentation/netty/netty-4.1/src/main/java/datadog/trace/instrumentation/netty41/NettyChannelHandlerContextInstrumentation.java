package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.NettyChannelPipelineInstrumentation.ADDITIONAL_INSTRUMENTATION_NAMES;
import static datadog.trace.instrumentation.netty41.NettyChannelPipelineInstrumentation.INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator;
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator;
import io.netty.channel.ChannelHandlerContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class NettyChannelHandlerContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public NettyChannelHandlerContextInstrumentation() {
    super(INSTRUMENTATION_NAME, ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.netty.channel.ChannelHandlerContext";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AttributeKeys",
      packageName + ".client.NettyHttpClientDecorator",
      packageName + ".server.ResponseExtractAdapter",
      packageName + ".server.NettyHttpServerDecorator",
      packageName + ".server.NettyHttpServerDecorator$NettyBlockResponseFunction",
      packageName + ".server.BlockingResponseHandler",
      packageName + ".server.BlockingResponseHandler$IgnoreAllWritesHandler",
      packageName + ".server.HttpServerRequestTracingHandler",
      packageName + ".server.HttpServerResponseTracingHandler",
      packageName + ".server.HttpServerTracingHandler"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        // this may be overly aggressive:
        isMethod().and(nameStartsWith("fire")).and(isPublic()),
        NettyChannelHandlerContextInstrumentation.class.getName() + "$FireAdvice");
  }

  public static class FireAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope scopeSpan(@Advice.This final ChannelHandlerContext ctx) {
      final Context storedContext = ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get();
      final AgentSpan channelSpan = spanFromContext(storedContext);
      if (channelSpan == null || channelSpan == activeSpan()) {
        // don't modify the scope
        return noopScope();
      }
      return activateSpan(channelSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void close(@Advice.Enter final AgentScope scope) {
      scope.close();
    }

    private void muzzleCheck() {
      NettyHttpClientDecorator.DECORATE.afterStart(null);
      NettyHttpServerDecorator.DECORATE.afterStart(null);
    }
  }
}
