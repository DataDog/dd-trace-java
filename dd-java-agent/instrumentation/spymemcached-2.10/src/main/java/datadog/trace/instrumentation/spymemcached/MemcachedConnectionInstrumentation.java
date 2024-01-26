package datadog.trace.instrumentation.spymemcached;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.net.InetSocketAddress;
import net.bytebuddy.asm.Advice;
import net.spy.memcached.MemcachedNode;
import net.spy.memcached.internal.OperationFuture;

@AutoService(Instrumenter.class)
public class MemcachedConnectionInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public MemcachedConnectionInstrumentation() {
    super("spymemcached");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MemcacheClientDecorator",
    };
  }

  public static class AddOperationAdvice {
    @Advice.OnMethodEnter
    public static void methodEnter(@Advice.Argument(0) final MemcachedNode node) {
      final AgentSpan span = AgentTracer.activeSpan();
      if (span != null && node != null && node.getSocketAddress() instanceof InetSocketAddress) {
        MemcacheClientDecorator.DECORATE.onPeerConnection(
            span, (InetSocketAddress) node.getSocketAddress());
      }
    }

    public static void muzzleCheck(OperationFuture operationFuture) {
      // before 2.10.4 futures are not completing correctly. We stick at this as minimum version
      operationFuture.signalComplete();
    }
  }
}
