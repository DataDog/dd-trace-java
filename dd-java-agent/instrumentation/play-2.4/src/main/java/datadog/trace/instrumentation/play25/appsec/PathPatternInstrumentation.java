package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;

/** @see play.core.routing.PathPattern#apply(String) */
@AutoService(Instrumenter.class)
public class PathPatternInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public PathPatternInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play25only";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MuzzleReferences.PLAY_25_ONLY;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PathExtractionHelpers",
    };
  }

  @Override
  public String instrumentedType() {
    return "play.core.routing.PathPattern";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("apply")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class))
            .and(returns(named("scala.Option"))),
        packageName + ".PathPatternApplyAdvice");
  }
}
