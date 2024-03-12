package datadog.trace.instrumentation.openlineage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.openlineage.client.OpenLineage;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class OpenLineageInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public OpenLineageInstrumentation() {
    super("openlineage");
  }

  @Override
  protected boolean defaultEnabled() {
    return true;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OpenLineageDecorator",
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.openlineage.client.transports.Transport";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("emit"))
            .and(takesArgument(0, named("io.openlineage.client.OpenLineage$RunEvent"))),
        OpenLineageInstrumentation.class.getName() + "$EmitAdvice");
  }

  public static class EmitAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) OpenLineage.RunEvent event) {
      OpenLineageDecorator.onEvent(event);
    }
  }
}
