package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.util.ByteString;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import play.mvc.Http;

/** @see play.mvc.BodyParser.TolerantJson#parse(Http.RequestHeader, ByteString) */
@AutoService(Instrumenter.class)
public class TolerantJsonInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public TolerantJsonInstrumentation() {
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
    return "play.mvc.BodyParser$TolerantJson";
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
        named("parse")
            .and(takesArguments(2))
            .and(takesArgument(0, named("play.mvc.Http$RequestHeader")))
            .and(takesArgument(1, named("akka.util.ByteString")))
            .and(returns(named("com.fasterxml.jackson.databind.JsonNode"))),
        packageName + ".BodyParserTolerantJsonParseAdvice");
  }
}
