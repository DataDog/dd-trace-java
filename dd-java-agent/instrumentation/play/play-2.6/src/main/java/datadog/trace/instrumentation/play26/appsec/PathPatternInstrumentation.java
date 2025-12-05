package datadog.trace.instrumentation.play26.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.instrumentation.play26.MuzzleReferences;
import net.bytebuddy.asm.Advice;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.util.Either;

/**
 * @see play.core.routing.PathPattern#apply(String)
 */
@AutoService(InstrumenterModule.class)
public class PathPatternInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PathPatternInstrumentation() {
    super("play");
  }

  @Override
  public String muzzleDirective() {
    return "play26Plus";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PathExtractionHelpers",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return MuzzleReferences.PLAY_26_PLUS;
  }

  @Override
  public String instrumentedType() {
    return "play.core.routing.PathPattern";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply")
            .and(not(isStatic()))
            .and(takesArguments(1))
            .and(takesArgument(0, String.class))
            .and(returns(named("scala.Option"))),
        PathPatternInstrumentation.class.getName() + "$ApplyAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  static class ApplyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Return(readOnly = false)
            scala.Option<
                    scala.collection.immutable.Map<String, scala.util.Either<Throwable, String>>>
                ret,
        @Advice.Thrown(readOnly = false) Throwable t,
        @ActiveRequestContext RequestContext reqCtx) {
      if (t != null) {
        return;
      }
      if (ret.isEmpty()) {
        return;
      }

      java.util.Map<String, Object> conv = new java.util.HashMap<>();

      Iterator<Tuple2<String, Either<Throwable, String>>> iterator = ret.get().iterator();
      while (iterator.hasNext()) {
        Tuple2<String, Either<Throwable, String>> next = iterator.next();
        Either<Throwable, String> value = next._2();
        if (value.isLeft()) {
          continue;
        }

        conv.put(next._1(), value.right().get());
      }

      BlockingException blockingException =
          PathExtractionHelpers.callRequestPathParamsCallback(reqCtx, conv, "PathPattern#apply");
      t = blockingException;
    }
  }
}
