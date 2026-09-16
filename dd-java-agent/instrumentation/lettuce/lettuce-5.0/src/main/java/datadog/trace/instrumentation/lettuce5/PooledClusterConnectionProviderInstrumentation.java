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
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import java.util.Collections;
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

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <T extends StatefulConnection> void onExit(
        @Advice.Return(readOnly = false) CompletableFuture<T> connectionFuture) {
      final AgentSpan span = activeSpan();
      if (!MasterReplicaConnectionHelper.isRedisClientSpan(span) || connectionFuture == null) {
        return;
      }

      connectionFuture =
          MasterReplicaConnectionHelper.onConnectionFuture(
              span,
              connectionFuture,
              InstrumentationContext.get(StatefulConnection.class, RedisURI.class));
    }
  }
}
