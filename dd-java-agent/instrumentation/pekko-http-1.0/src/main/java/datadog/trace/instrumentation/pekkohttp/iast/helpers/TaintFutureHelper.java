package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import scala.compat.java8.JFunction1;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

public class TaintFutureHelper {
  public static <T> Future<T> wrapFuture(
      Future<T> f, Object input, PropagationModule mod, ExecutionContext ec) {
    JFunction1<T, T> mapf =
        t -> {
          final TaintedObjects to = IastContext.Provider.taintedObjects();
          if (to != null) {
            mod.taintObjectIfTainted(to, t, input);
          }
          return t;
        };
    return f.map(mapf, ec);
  }
}
