package datadog.trace.instrumentation.datastax.cassandra38;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.EndPoint;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CassandraClusterInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

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
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("com.datastax.driver.core.EndPoint");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ContactPointsUtil",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(1, named("java.util.List"))),
        getClass().getName() + "$CassandraManagerConstructorAdvice");
  }

  public static class CassandraManagerConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstruct(
        @Advice.This Cluster self, @Advice.Argument(1) final List<EndPoint> contactPoints)
        throws Exception {
      InstrumentationContext.get(Cluster.class, String.class)
          .put(self, ContactPointsUtil.fromEndPointList(contactPoints));
    }
  }
}
