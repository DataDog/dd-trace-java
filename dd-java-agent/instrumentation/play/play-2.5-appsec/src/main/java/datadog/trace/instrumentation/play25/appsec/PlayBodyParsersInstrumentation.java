package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

/**
 * @see play.api.mvc.BodyParsers.parse$#tolerantText(long)
 */
@AutoService(InstrumenterModule.class)
public class PlayBodyParsersInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public PlayBodyParsersInstrumentation() {
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
  public String[] knownMatchingTypes() {
    return new String[] {
      "play.api.mvc.BodyParsers$parse$",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BodyParserHelpers",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("tolerantText")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, long.class))
            .and(returns(named("play.api.mvc.BodyParser"))),
        packageName + ".PlayBodyParsersTolerantTextAdvice");
    transformer.applyAdvice(
        named("tolerantJson")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, int.class))
            .and(returns(named("play.api.mvc.BodyParser"))),
        packageName + ".PlayBodyParsersTolerantJsonAdvice");
    transformer.applyAdvice(
        named("tolerantFormUrlEncoded")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, int.class))
            .and(returns(named("play.api.mvc.BodyParser"))),
        packageName + ".PlayBodyParsersTolerantFormUrlEncodedAdvice");
    transformer.applyAdvice(
        named("multipartFormData")
            .and(not(isStatic()))
            .and(takesArguments(2))
            .and(takesArgument(0, named("scala.Function1")))
            .and(takesArgument(1, long.class))
            .and(returns(named("play.api.mvc.BodyParser"))),
        packageName + ".PlayBodyParsersMultipartFormDataAdvice");
  }
}
