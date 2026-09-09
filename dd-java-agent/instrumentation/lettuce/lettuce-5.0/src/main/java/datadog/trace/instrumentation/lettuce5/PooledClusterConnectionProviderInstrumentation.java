package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.models.role.RedisNodeDescription;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

/**
 * Decorates Redis cluster command spans with the physical node selected for the command key slot.
 *
 * <p>Cluster command spans are started before Lettuce resolves the slot owner and applies {@code
 * ReadFrom}. This tracks the {@link RedisURI} of physical cluster node connections, then tags the
 * active command span when Lettuce returns the selected connection.
 */
@AutoService(InstrumenterModule.class)
public class PooledClusterConnectionProviderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PooledClusterConnectionProviderInstrumentation() {
    super("lettuce", "lettuce-5");
  }

  @Override
  public String instrumentedType() {
    return "io.lettuce.core.cluster.PooledClusterConnectionProvider";
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
      packageName + ".ConnectionContextFunction",
      packageName + ".LettuceInstrumentationUtil"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            // Synchronous getConnection delegates here after resolving the command slot.
            .and(named("getConnectionAsync"))
            .and(takesArguments(2))
            .and(takesArgument(1, int.class))
            .and(returns(named("java.util.concurrent.CompletableFuture"))),
        PooledClusterConnectionProviderInstrumentation.class.getName() + "$ConnectionAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getWriteConnection")).and(takesArguments(1)),
        PooledClusterConnectionProviderInstrumentation.class.getName() + "$WriteConnectionAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getReadFromConnections")).and(takesArguments(1)),
        PooledClusterConnectionProviderInstrumentation.class.getName() + "$ReadConnectionAdvice");
  }

  public static class ConnectionAdvice {

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

  public static class WriteConnectionAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.FieldValue("partitions") final Partitions partitions,
        @Advice.Argument(0) final int slot,
        @Advice.Return(readOnly = false)
            CompletableFuture<? extends StatefulConnection> connectionFuture) {
      if (partitions == null || connectionFuture == null) {
        return;
      }

      final RedisClusterNode node = partitions.getPartitionBySlot(slot);
      if (node == null) {
        return;
      }

      final RedisURI redisURI = node.getUri();
      if (redisURI != null) {
        connectionFuture =
            connectionFuture.thenApply(
                new ConnectionContextFunction(
                    redisURI,
                    InstrumentationContext.get(StatefulConnection.class, RedisURI.class)));
      }
    }
  }

  public static class ReadConnectionAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) final List<RedisNodeDescription> selection,
        @Advice.Return final CompletableFuture<? extends StatefulConnection>[] connectionFutures) {
      if (selection == null || connectionFutures == null) {
        return;
      }

      final ContextStore<StatefulConnection, RedisURI> contextStore =
          InstrumentationContext.get(StatefulConnection.class, RedisURI.class);
      for (int i = 0; i < selection.size() && i < connectionFutures.length; i++) {
        final RedisURI redisURI = selection.get(i).getUri();
        if (redisURI != null && connectionFutures[i] != null) {
          connectionFutures[i] =
              connectionFutures[i].thenApply(new ConnectionContextFunction(redisURI, contextStore));
        }
      }
    }
  }
}
