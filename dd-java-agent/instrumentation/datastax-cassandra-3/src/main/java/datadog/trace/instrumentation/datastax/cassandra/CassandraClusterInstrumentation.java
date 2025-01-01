package datadog.trace.instrumentation.datastax.cassandra;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.datastax.driver.core.Cluster;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class CassandraClusterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public CassandraClusterInstrumentation() {
    super("cassandra");
  }

  @Override
  public String instrumentedType() {
    return "com.datastax.driver.core.Cluster";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.datastax.driver.core.Cluster", String.class.getName());
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return not(hasClassNamed("com.datastax.driver.core.EndPoint"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ContactPointsUtil",
    };
  }

  @Override
  public String muzzleDirective() {
    return "cluster";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(1, named("java.util.List"))),
        getClass().getName() + "$CassandraManagerConstructorAdvice");
  }

  public static class CassandraManagerConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruct(
        @Advice.This Cluster self, @Advice.Argument(1) final List<InetSocketAddress> contactPoints)
        throws Exception {
      InstrumentationContext.get(Cluster.class, String.class)
          .put(self, ContactPointsUtil.fromInetSocketList(contactPoints));
    }
  }
}
