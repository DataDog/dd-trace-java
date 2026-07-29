package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

/**
 * Master/replica APIs expose a routing connection ({@code
 * StatefulRedisMasterReplicaConnectionImpl}; legacy {@code MasterSlave} wraps it in {@code
 * MasterSlaveConnectionWrapper}). The real node connection is selected only after a command span
 * has started and the command is dispatched, so this decorates the active span with the RedisURI
 * that is available on the real connection, not the wrapper.
 */
@AutoService(InstrumenterModule.class)
public class MasterReplicaConnectionProviderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public MasterReplicaConnectionProviderInstrumentation() {
    super("lettuce", "lettuce-5");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      // Legacy Lettuce 5.x
      "io.lettuce.core.masterslave.MasterSlaveConnectionProvider",
      // Transitional Lettuce 6.0 provider
      "io.lettuce.core.masterreplica.UpstreamReplicaConnectionProvider",
      // Lettuce 6.1+
      "io.lettuce.core.masterreplica.MasterReplicaConnectionProvider"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.lettuce.core.api.StatefulConnection", "io.lettuce.core.RedisURI");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LettuceClientDecorator",
      packageName + ".MasterReplicaConnectionHelper",
      packageName + ".LettuceInstrumentationUtil"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Intent argument types move across Lettuce versions, but only the returned connection is used.
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("getConnection"))
            .and(takesArguments(1))
            .and(returns(named("io.lettuce.core.api.StatefulRedisConnection"))),
        MasterReplicaConnectionProviderInstrumentation.class.getName() + "$SyncAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("getConnectionAsync"))
            .and(takesArguments(1))
            .and(returns(named("java.util.concurrent.CompletableFuture"))),
        MasterReplicaConnectionProviderInstrumentation.class.getName() + "$AsyncAdvice");
  }

  public static class SyncAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return final StatefulRedisConnection<?, ?> connection) {
      final AgentSpan span = activeSpan();
      if (!MasterReplicaConnectionHelper.isRedisClientSpan(span)) {
        return;
      }

      MasterReplicaConnectionHelper.onConnection(
          span, connection, InstrumentationContext.get(StatefulConnection.class, RedisURI.class));
    }
  }

  public static class AsyncAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return final CompletableFuture<? extends StatefulConnection> connectionFuture) {
      final AgentSpan span = activeSpan();
      if (!MasterReplicaConnectionHelper.isRedisClientSpan(span) || connectionFuture == null) {
        return;
      }

      connectionFuture.whenComplete(
          MasterReplicaConnectionHelper.onConnectionComplete(
              span, InstrumentationContext.get(StatefulConnection.class, RedisURI.class)));
    }
  }
}
