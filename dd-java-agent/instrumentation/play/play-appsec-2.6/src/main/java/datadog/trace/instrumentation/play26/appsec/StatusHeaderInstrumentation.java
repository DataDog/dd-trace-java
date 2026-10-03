package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.instrumentation.play26.MuzzleReferences;
import net.bytebuddy.asm.Advice;
import play.mvc.StatusHeader;

@AutoService(InstrumenterModule.class)
public class StatusHeaderInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public StatusHeaderInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Plus";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MuzzleReferences.PLAY_26_PLUS; // force failure in <2.6
  }

  @Override
  public String instrumentedType() {
    return "play.mvc.StatusHeader";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BodyParserHelpers", packageName + ".BodyParserHelpers$ScalaIteratorAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("sendJson").and(takesArgument(0, named("com.fasterxml.jackson.databind.JsonNode"))),
        StatusHeaderInstrumentation.class.getName() + "$StatusHeaderSendJsonAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class StatusHeaderSendJsonAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void before(
        @Advice.Argument(0) final JsonNode json,
        @ActiveRequestContext final RequestContext reqCtx) {

      if (CallDepthThreadLocalMap.incrementCallDepth(StatusHeader.class) > 0) {
        return;
      }

      if (json == null) {
        return;
      }

      BodyParserHelpers.handleResponseBody(reqCtx, json, "StatusHeader/sendJson");
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void after() {
      CallDepthThreadLocalMap.decrementCallDepth(StatusHeader.class);
    }
  }
}
