package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class ConnectionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public ConnectionInstrumentation() {
    super("r2dbc");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.r2dbc.spi.Connection";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("io.r2dbc.spi.Connection"));
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> stores = new HashMap<>();
    stores.put("io.r2dbc.spi.Statement", packageName + ".R2dbcStatementInfo");
    stores.put("io.r2dbc.spi.Connection", packageName + ".R2dbcConnectionInfo");
    stores.put("io.r2dbc.spi.Batch", packageName + ".R2dbcConnectionInfo");
    return stores;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".R2dbcConnectionInfo",
      packageName + ".R2dbcStatementInfo",
      packageName + ".R2dbcDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("createStatement")
            .and(isPublic())
            .and(takesArguments(1))
            .and(takesArgument(0, String.class)),
        packageName + ".ConnectionCreateStatementAdvice");
    transformer.applyAdvice(
        named("createBatch").and(isPublic()).and(takesArguments(0)),
        packageName + ".ConnectionCreateBatchAdvice");
  }
}
