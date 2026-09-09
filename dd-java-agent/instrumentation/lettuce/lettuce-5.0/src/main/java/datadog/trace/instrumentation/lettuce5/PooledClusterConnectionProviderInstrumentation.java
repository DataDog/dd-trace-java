package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import net.bytebuddy.asm.Advice;

/**
 * Decorates Redis cluster command spans with the node selected for the command key slot.
 *
 * <p>Cluster command spans are started before Lettuce resolves the slot owner. This hooks the
 * provider lookup that has both the routed slot and the current cluster partitions, then tags the
 * active span with the {@link RedisURI} of the node serving that slot.
 *
 * <p>This complements the connection context store, which captures RedisURI per physical connection
 * but not this per-command slot decision.
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
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LettuceClientDecorator",
      packageName + ".MasterReplicaConnectionHelper",
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
  }

  public static class ConnectionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.FieldValue("partitions") final Partitions partitions,
        @Advice.Argument(1) final int slot) {
      final AgentSpan span = activeSpan();
      if (!MasterReplicaConnectionHelper.isRedisClientSpan(span) || partitions == null) {
        return;
      }

      final RedisClusterNode node = partitions.getPartitionBySlot(slot);
      if (node == null) {
        return;
      }

      final RedisURI redisURI = node.getUri();
      if (redisURI != null) {
        DECORATE.onConnection(span, redisURI);
      }
    }
  }
}
