package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import io.lettuce.core.ConnectionFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class RedisClusterClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public RedisClusterClientInstrumentation() {
    super("lettuce", "lettuce-5");
  }

  @Override
  public String instrumentedType() {
    return "io.lettuce.core.cluster.RedisClusterClient";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.lettuce.core.api.StatefulConnection", "io.lettuce.core.RedisURI");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ClusterConnectionContextFunction"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("connectToNodeAsync"))
            .and(takesArguments(4))
            .and(takesArgument(1, String.class))
            .and(returns(named("io.lettuce.core.ConnectionFuture"))),
        RedisClusterClientInstrumentation.class.getName() + "$ConnectToNodeAdvice");
  }

  public static class ConnectToNodeAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <T extends StatefulConnection> void onExit(
        @Advice.Return(readOnly = false) ConnectionFuture<T> connectionFuture) {
      if (connectionFuture == null) {
        return;
      }

      connectionFuture =
          connectionFuture.thenApply(
              new ClusterConnectionContextFunction<T>(
                  connectionFuture,
                  InstrumentationContext.get(StatefulConnection.class, RedisURI.class)));
    }
  }
}
