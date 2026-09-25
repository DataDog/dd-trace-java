package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.play26.appsec.BodyParserHelpers.jsValueToJavaObject;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.instrumentation.play26.MuzzleReferences;
import net.bytebuddy.asm.Advice;
import play.api.libs.json.JsValue;

@AutoService(InstrumenterModule.class)
public class ResultsStatusInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ResultsStatusInstrumentation() {
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
    return "play.api.mvc.Results$Status";
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
        named("apply"), ResultsStatusInstrumentation.class.getName() + "$ResultsStatusApplyAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class ResultsStatusApplyAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void after(
        @Advice.Argument(0) final Object content, @ActiveRequestContext RequestContext reqCtx) {

      if (!(content instanceof JsValue)) {
        return;
      }

      BodyParserHelpers.handleResponseBody(
          reqCtx, jsValueToJavaObject((JsValue) content), "Results$Status/apply");
    }
  }
}
