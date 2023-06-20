package datadog.trace.instrumentation.couchbase_32.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.couchbase.client.core.Core;
import com.couchbase.client.core.cnc.RequestSpan;
import com.couchbase.client.core.util.ConnectionString;
import com.couchbase.client.java.Cluster;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ClusterInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public ClusterInstrumentation() {
    super("couchbase", "couchbase-3", "couchbase-peer");
  }

  @Override
  public String instrumentedType() {
    return "com.couchbase.client.java.Cluster";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ClusterHelper", packageName + ".TracingInfo",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.couchbase.client.core.Core", packageName + ".TracingInfo");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("connect"))
            .and(takesArgument(0, named("java.lang.String"))),
        getClass().getName() + "$ClusterConnectAdvice");
  }

  public static class ClusterConnectAdvice {

    public static void muzzleCheck(RequestSpan requestSpan) {
      requestSpan.status(RequestSpan.StatusCode.ERROR);
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static String beforeConnect(
        @Advice.Argument(value = 0, readOnly = false) String connectionString) {
      if (connectionString != null
          && CallDepthThreadLocalMap.incrementCallDepth(Cluster.class) == 0) {
        try {
          final ConnectionString parsed = ConnectionString.create(connectionString);
          if (parsed.params().containsKey(ClusterHelper.PARAM_INFERRED_PEER_SERVICE)) {
            connectionString = ClusterHelper.removeDdTracingFromConnectionString(connectionString);
            return parsed.params().get(ClusterHelper.PARAM_INFERRED_PEER_SERVICE);
          }
        } catch (Exception e) {
          // maybe log
        }
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConnect(
        @Advice.Enter final String peerService, @Advice.Return Cluster cluster) {
      CallDepthThreadLocalMap.reset(Cluster.class);
      if (peerService == null || cluster == null || cluster.core() == null) {
        return;
      }
      final ContextStore<Core, TracingInfo> contextStore =
          InstrumentationContext.get(Core.class, TracingInfo.class);
      TracingInfo ti = contextStore.get(cluster.core());
      if (ti == null) {
        ti = new TracingInfo(null, peerService);
        contextStore.put(cluster.core(), ti);
      } else {
        ti.setPeerService(peerService);
      }
    }
  }
}
