package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator;
import io.netty.channel.ChannelFuture;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class ChannelFutureListenerInstrumentation extends Instrumenter.Default {

  public ChannelFutureListenerInstrumentation() {
    super(
        NettyChannelPipelineInstrumentation.INSTRUMENTATION_NAME,
        NettyChannelPipelineInstrumentation.ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("io.netty.channel.ChannelFutureListener");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.netty.channel.ChannelFutureListener"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AttributeKeys",
      packageName + ".AttributeKeys$1",
      // client helpers
      packageName + ".client.NettyHttpClientDecorator",
      packageName + ".client.NettyResponseInjectAdapter",
      packageName + ".client.HttpClientRequestTracingHandler",
      packageName + ".client.HttpClientResponseTracingHandler",
      packageName + ".client.HttpClientTracingHandler",
      // server helpers
      packageName + ".server.NettyHttpServerDecorator",
      packageName + ".server.NettyRequestExtractAdapter",
      packageName + ".server.HttpServerRequestTracingHandler",
      packageName + ".server.HttpServerResponseTracingHandler",
      packageName + ".server.HttpServerTracingHandler"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("operationComplete"))
            .and(takesArgument(0, named("io.netty.channel.ChannelFuture"))),
        ChannelFutureListenerInstrumentation.class.getName() + "$OperationCompleteAdvice");
  }

  public static class OperationCompleteAdvice {
    @Advice.OnMethodEnter
    public static TraceScope activateScope(@Advice.Argument(0) final ChannelFuture future) {
      /*
      Idea here is:
       - To return scope only if we have captured it.
       - To capture scope only in case of error.
       */
      final Throwable cause = future.cause();
      if (cause == null) {
        return null;
      }
      final TraceScope.Continuation continuation =
          future
              .channel()
              .attr(AttributeKeys.PARENT_CONNECT_CONTINUATION_ATTRIBUTE_KEY)
              .getAndRemove();
      if (continuation == null) {
        return null;
      }
      final TraceScope parentScope = continuation.activate();

      final AgentSpan errorSpan = startSpan("netty.connect").setTag(Tags.COMPONENT, "netty");
      try (final AgentScope scope = activateSpan(errorSpan, false)) {
        NettyHttpServerDecorator.DECORATE.onError(errorSpan, cause);
        NettyHttpServerDecorator.DECORATE.beforeFinish(errorSpan);
        errorSpan.finish();
      }

      return parentScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void deactivateScope(@Advice.Enter final TraceScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
