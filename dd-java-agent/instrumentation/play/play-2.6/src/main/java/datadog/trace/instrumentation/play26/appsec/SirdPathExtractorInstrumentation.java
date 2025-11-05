package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.instrumentation.play26.MuzzleReferences;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import scala.collection.immutable.List;

/** @see play.api.routing.sird.PathExtractor */
@AutoService(InstrumenterModule.class)
public class SirdPathExtractorInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public SirdPathExtractorInstrumentation() {
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
    return "play.api.routing.sird.PathExtractor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("extract")
            .and(takesArguments(1))
            .and(takesArgument(0, String.class))
            .and(returns(named("scala.Option"))),
        SirdPathExtractorInstrumentation.class.getName() + "$ExtractAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PathExtractionHelpers",
    };
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class ExtractAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return scala.Option<scala.collection.immutable.List<String>> ret,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (ret.isEmpty() || t != null) {
        return;
      }

      Map<String, Object> conv = new HashMap<>();
      List<String> stringList = ret.get();
      for (int i = 0; i < stringList.size(); i++) {
        conv.put(Integer.toString(i), stringList.apply(i));
      }

      t =
          PathExtractionHelpers.callRequestPathParamsCallback(
              reqCtx, conv, "sird.PathExtractor#extract");
    }
  }
}
