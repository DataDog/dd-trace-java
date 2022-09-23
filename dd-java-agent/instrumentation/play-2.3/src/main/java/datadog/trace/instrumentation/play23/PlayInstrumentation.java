package datadog.trace.instrumentation.play23;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class PlayInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public PlayInstrumentation() {
    super("play", "play-action");
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
      packageName + ".RequestURIDataAdapter"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("apply")
            .and(takesArgument(0, named("play.api.mvc.Request")))
            .and(returns(named("scala.concurrent.Future"))),
        packageName + ".PlayAdvice");
  }
}
