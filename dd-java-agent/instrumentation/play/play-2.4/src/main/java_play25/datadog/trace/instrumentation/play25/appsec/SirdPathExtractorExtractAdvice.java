package datadog.trace.instrumentation.play25.appsec;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import scala.collection.immutable.List;

/** @see play.api.routing.sird.PathExtractor#extract(java.lang.String) */
@RequiresRequestContext(RequestContextSlot.APPSEC)
public class SirdPathExtractorExtractAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.Return scala.Option<List<String>> ret,
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
