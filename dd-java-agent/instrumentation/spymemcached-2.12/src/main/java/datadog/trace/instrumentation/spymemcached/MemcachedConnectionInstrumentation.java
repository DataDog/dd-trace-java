package datadog.trace.instrumentation.spymemcached;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.InetSocketAddress;
import net.bytebuddy.asm.Advice;
import net.spy.memcached.MemcachedNode;

@AutoService(Instrumenter.class)
public class MemcachedConnectionInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  private static final Reference GET_COMPLETION_LISTENER_REFERENCE =
      new Reference.Builder("net.spy.memcached.internal.GetCompletionListener").build();

  public MemcachedConnectionInstrumentation() {
    super("spymemcached");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {GET_COMPLETION_LISTENER_REFERENCE};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("addOperation"))
            .and(isProtected())
            .and(takesArgument(0, named("net.spy.memcached.MemcachedNode"))),
        MemcachedConnectionInstrumentation.class.getName() + "$AddOperationAdvice");
  }

  @Override
  public String instrumentedType() {
    return "net.spy.memcached.MemcachedConnection";
  }

  public static class AddOperationAdvice {
    @Advice.OnMethodEnter
    public static void methodEnter(@Advice.Argument(0) final MemcachedNode node) {
      if (node != null && node.getSocketAddress() instanceof InetSocketAddress) {
        MemcacheClientDecorator.DECORATE.onPeerConnection(
            AgentTracer.activeSpan(), (InetSocketAddress) node.getSocketAddress());
      }
    }
  }
}
