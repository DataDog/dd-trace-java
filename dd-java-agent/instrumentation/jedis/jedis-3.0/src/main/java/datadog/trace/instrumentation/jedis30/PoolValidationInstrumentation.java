package datadog.trace.instrumentation.jedis30;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Stops Jedis 3.x pool liveness checks from producing spans.
 *
 * <p>The pool checks a connection by sending {@code PING} on borrow, on return and from the idle
 * evictor, which {@code JedisPoolConfig} turns on by default. The evictor's checks run with no
 * active trace, so each became a standalone root span. The advice runs each check under a
 * black-hole span, so the {@code PING} creates no span.
 *
 * <p>Jedis 4.x needs no equivalent: its pool sends {@code PING} through {@code sendCommand}, which
 * that integration does not trace.
 */
@AutoService(InstrumenterModule.class)
public final class PoolValidationInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public PoolValidationInstrumentation() {
    super("jedis", "redis", "redis-pool-validation");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Jedis 3.x only: ProtocolCommand arrived in 3.0, CommandObject in 4.0
    return hasClassNamed("redis.clients.jedis.commands.ProtocolCommand")
        .and(not(hasClassNamed("redis.clients.jedis.CommandObject")));
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "redis.clients.jedis.JedisFactory", "redis.clients.jedis.ShardedJedisPool$ShardedJedisFactory"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("validateObject"), PoolValidationInstrumentation.class.getName() + "$MuteAdvice");
  }

  public static class MuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter() {
      return activateSpan(blackholeSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
