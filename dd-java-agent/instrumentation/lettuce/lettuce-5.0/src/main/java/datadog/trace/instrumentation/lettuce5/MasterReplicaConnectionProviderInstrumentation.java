package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

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
      // Legacy Lettuce [5,7)
      "io.lettuce.core.masterslave.MasterSlaveConnectionProvider",
      // Newer Lettuce [7,)
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
    // Legacy provider, used directly by MasterSlaveChannelWriter.
    transformer.applyAdvice(
        isMethod()
            .and(named("getConnection"))
            .and(
                takesArgument(
                    0, named("io.lettuce.core.masterslave.MasterSlaveConnectionProvider$Intent")))
            .and(returns(named("io.lettuce.core.api.StatefulRedisConnection"))),
        MasterReplicaConnectionProviderInstrumentation.class.getName() + "$SyncAdvice");
    // Newer masterreplica provider still exposes a synchronous accessor for blocking callers.
    transformer.applyAdvice(
        isMethod()
            .and(named("getConnection"))
            .and(takesArgument(0, named("io.lettuce.core.protocol.ConnectionIntent")))
            .and(returns(named("io.lettuce.core.api.StatefulRedisConnection"))),
        MasterReplicaConnectionProviderInstrumentation.class.getName() + "$SyncAdvice");
    // Newer command writers use the async accessor, so update the command span when it resolves.
    transformer.applyAdvice(
        isMethod()
            .and(named("getConnectionAsync"))
            .and(takesArgument(0, named("io.lettuce.core.protocol.ConnectionIntent")))
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
