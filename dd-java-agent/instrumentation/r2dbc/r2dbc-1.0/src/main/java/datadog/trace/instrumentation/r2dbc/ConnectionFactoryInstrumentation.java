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
public final class ConnectionFactoryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public ConnectionFactoryInstrumentation() {
    super("r2dbc", "r2dbc-spi");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.r2dbc.spi.ConnectionFactory";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("io.r2dbc.spi.ConnectionFactory"));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.r2dbc.spi.Connection", packageName + ".R2dbcConnectionInfo");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".R2dbcConnectionInfo",
      packageName + ".R2dbcConnectionInfoExtractor",
      packageName + ".MetadataWrappingPublisher",
      packageName + ".MetadataWrappingPublisher$MetadataWrappingSubscriber",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("create").and(isPublic()).and(takesArguments(0)),
        packageName + ".ConnectionFactoryCreateAdvice");
  }
}
