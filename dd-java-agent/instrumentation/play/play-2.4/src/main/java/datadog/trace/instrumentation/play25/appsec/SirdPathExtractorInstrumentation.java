package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

/**
 * @see play.api.routing.sird.PathExtractor
 */
@AutoService(InstrumenterModule.class)
public class SirdPathExtractorInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public SirdPathExtractorInstrumentation() {
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
  public String instrumentedType() {
    return "play.api.routing.sird.PathExtractor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("extract", "play$api$routing$sird$PathExtractor$$extract")
            .and(takesArguments(1))
            .and(takesArgument(0, String.class))
            .and(returns(named("scala.Option"))),
        packageName + ".SirdPathExtractorExtractAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PathExtractionHelpers",
    };
  }
}
