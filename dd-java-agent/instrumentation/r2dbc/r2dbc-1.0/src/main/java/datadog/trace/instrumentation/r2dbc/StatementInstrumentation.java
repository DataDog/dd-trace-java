package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class StatementInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public StatementInstrumentation() {
    super("r2dbc");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.r2dbc.spi.Statement";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("io.r2dbc.spi.Statement"));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("io.r2dbc.spi.Statement", packageName + ".R2dbcStatementInfo");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".R2dbcConnectionInfo",
      packageName + ".R2dbcStatementInfo",
      packageName + ".R2dbcDecorator",
      packageName + ".TracingPublisher",
      packageName + ".TracingPublisher$TracingSubscriber",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute").and(isPublic()).and(takesArguments(0)),
        packageName + ".StatementExecuteAdvice");
  }
}
