package datadog.trace.instrumentation.play24;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class PlayInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public PlayInstrumentation() {
    super("play");
  }

  @Override
  public String hierarchyMarkerType() {
    return "play.api.mvc.Action";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PlayHeaders",
      packageName + ".PlayHeaders$Request",
      packageName + ".PlayHeaders$Result",
      packageName + ".PlayHttpServerDecorator",
      packageName + ".RequestCompleteCallback",
      packageName + ".RequestURIDataAdapter",
      packageName + ".RequestHelper",
      packageName + ".RequestHelper$SFunction0"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply")
            .and(takesArgument(0, named("play.api.mvc.Request")))
            .and(returns(named("scala.concurrent.Future"))),
        packageName + ".PlayAdvice");
  }
}
