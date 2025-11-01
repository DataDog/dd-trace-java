package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.NETTY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.NETTY_CONNECT;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator;
import io.netty.channel.ChannelFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ChannelFutureListenerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public ChannelFutureListenerInstrumentation() {
    super(
        NettyChannelPipelineInstrumentation.INSTRUMENTATION_NAME,
        NettyChannelPipelineInstrumentation.ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.netty.channel.ChannelFutureListener";
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
      packageName + ".server.HttpServerTracingHandler"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("operationComplete"))
            .and(takesArgument(0, named("io.netty.channel.ChannelFuture"))),
        ChannelFutureListenerInstrumentation.class.getName() + "$OperationCompleteAdvice");
  }

  public static class OperationCompleteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope activateScope(@Advice.Argument(0) final ChannelFuture future) {
      /*
      Idea here is:
       - To return scope only if we have captured it.
       - To capture scope only in case of error.
       */
      final Throwable cause = future.cause();
      if (cause == null) {
        return null;
      }
      final AgentScope.Continuation continuation =
          future.channel().attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY).getAndRemove();
      if (continuation == null) {
        return null;
      }
      final AgentScope parentScope = continuation.activate();

      final AgentSpan errorSpan = startSpan(NETTY_CONNECT).setTag(Tags.COMPONENT, "netty");
      errorSpan.context().setIntegrationName(NETTY);
      try (final ContextScope scope = errorSpan.attach()) {
        NettyHttpServerDecorator.DECORATE.onError(errorSpan, cause);
        NettyHttpServerDecorator.DECORATE.beforeFinish(scope.context());
        errorSpan.finish();
      }

      return parentScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void deactivateScope(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
