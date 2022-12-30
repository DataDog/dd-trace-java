package datadog.trace.instrumentation.netty38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.netty38.NettyChannelPipelineInstrumentation.ADDITIONAL_INSTRUMENTATION_NAMES;
import static datadog.trace.instrumentation.netty38.NettyChannelPipelineInstrumentation.INSTRUMENTATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.jboss.netty.channel.Channel;

@AutoService(Instrumenter.class)
public class NettyChannelInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public NettyChannelInstrumentation() {
    super(INSTRUMENTATION_NAME, ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.jboss.netty.channel.Channel";
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
      packageName + ".ChannelTraceContext$Factory"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("connect"))
            .and(returns(named("org.jboss.netty.channel.ChannelFuture"))),
        NettyChannelInstrumentation.class.getName() + "$ChannelConnectAdvice");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "org.jboss.netty.channel.Channel", packageName + ".ChannelTraceContext");
  }

  public static class ChannelConnectAdvice extends AbstractNettyAdvice {
    @Advice.OnMethodEnter
    public static void addConnectContinuation(@Advice.This final Channel channel) {
      final AgentScope scope = activeScope();
      if (scope != null) {
        final AgentScope.Continuation continuation = scope.capture();
        if (continuation != null) {
          final ContextStore<Channel, ChannelTraceContext> contextStore =
              InstrumentationContext.get(Channel.class, ChannelTraceContext.class);

          if (contextStore
                  .putIfAbsent(channel, ChannelTraceContext.Factory.INSTANCE)
                  .getConnectionContinuation()
              != null) {
            continuation.cancel();
          } else {
            contextStore.get(channel).setConnectionContinuation(continuation);
          }
        }
      }
    }
  }
}
