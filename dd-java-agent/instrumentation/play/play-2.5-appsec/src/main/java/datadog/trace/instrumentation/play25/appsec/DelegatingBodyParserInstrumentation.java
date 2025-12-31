package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(InstrumenterModule.class)
public class DelegatingBodyParserInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public DelegatingBodyParserInstrumentation() {
    super("play");
  }

  @Override
  public String instrumentedType() {
    return "play.mvc.BodyParser$DelegatingBodyParser";
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
      packageName + ".JavaMultipartFormDataRegisterExcF",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(1))
            .and(takesArgument(0, named("play.mvc.Http$RequestHeader")))
            .and(returns(named("play.libs.streams.Accumulator"))),
        packageName + ".BodyParserDelegatingBodyParserApplyAdvice");
  }
}
