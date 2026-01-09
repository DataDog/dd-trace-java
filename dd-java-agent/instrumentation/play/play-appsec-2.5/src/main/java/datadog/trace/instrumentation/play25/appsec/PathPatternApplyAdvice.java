package datadog.trace.instrumentation.play25.appsec;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.instrumentation.play.appsec.PathExtractionHelpers;
import net.bytebuddy.asm.Advice;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.util.Either;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class PathPatternApplyAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.Return(readOnly = false)
          scala.Option<scala.collection.immutable.Map<String, Either<Throwable, String>>> ret,
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
