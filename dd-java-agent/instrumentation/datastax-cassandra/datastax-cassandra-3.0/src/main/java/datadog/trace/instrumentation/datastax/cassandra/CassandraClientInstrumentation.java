package datadog.trace.instrumentation.datastax.cassandra;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class CassandraClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CassandraClientInstrumentation() {
    super("cassandra");
  }

  @Override
  public String instrumentedType() {
    // Note: Cassandra has a large driver and we instrument single class in it.
    // The rest is ignored in the additional ignores of GlobalIgnoresMatcher
    return "com.datastax.driver.core.Cluster$Manager";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CassandraClientDecorator",
      packageName + ".TracingSession",
      packageName + ".TracingSession$SessionTransfomer",
      packageName + ".TracingSession$1",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.datastax.driver.core.Cluster", String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPrivate()).and(named("newSession")).and(takesArguments(0)),
        CassandraClientInstrumentation.class.getName() + "$CassandraClientAdvice");
  }

  public static class CassandraClientAdvice {
    /**
     * Strategy: each time we build a connection to a Cassandra cluster, the
     * com.datastax.driver.core.Cluster$Manager.newSession() method is called. The opentracing
     * contribution is a simple wrapper, so we just have to wrap the new session.
     *
     * @param session The fresh session to patch. This session is replaced with new session
     * @throws Exception
     */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void injectTracingSession(@Advice.Return(readOnly = false) Session session)
        throws Exception {
      // This should cover ours and OT's TracingSession
      if (session.getClass().getName().endsWith("cassandra.TracingSession")) {
        return;
      }
      session =
          new TracingSession(
              session,
              InstrumentationContext.get(Cluster.class, String.class).get(session.getCluster()));
    }
  }
}
