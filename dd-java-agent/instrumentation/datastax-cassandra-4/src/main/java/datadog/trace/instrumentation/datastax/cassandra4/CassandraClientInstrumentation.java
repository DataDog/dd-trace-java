package datadog.trace.instrumentation.datastax.cassandra4;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CassandraClientInstrumentation extends Instrumenter.Default {

  public CassandraClientInstrumentation() {
    super("cassandra");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.datastax.oss.driver.internal.core.session.DefaultSession");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CassandraClientDecorator", packageName + ".TracingSession"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("init"))
            .and(isStatic())
            .and(takesArguments(3))
            .and(returns(named("java.util.concurrent.CompletionStage"))),
        packageName + ".CassandraClientAdvice");
  }
}
