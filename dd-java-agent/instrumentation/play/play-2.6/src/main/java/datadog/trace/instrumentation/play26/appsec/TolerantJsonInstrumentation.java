package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.instrumentation.play26.MuzzleReferences;
import net.bytebuddy.asm.Advice;
import play.mvc.Http;

/**
 * @see play.mvc.BodyParser.TolerantJson#parse(Http.RequestHeader, ByteString)
 */
@AutoService(InstrumenterModule.class)
public class TolerantJsonInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public TolerantJsonInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Plus";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MuzzleReferences.PLAY_26_PLUS;
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
        TolerantJsonInstrumentation.class.getName() + "$ParseAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class ParseAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(@Advice.Return JsonNode ret, @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      try {
        BodyParserHelpers.handleJsonNode(ret, "TolerantJson#parse");
      } catch (BlockingException be) {
        t = be;
      }
    }
  }
}
