package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import scala.compat.java8.JFunction1;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

public class TaintFutureHelper {
  public static <T> Future<T> wrapFuture(
      Future<T> f, Object input, PropagationModule mod, ExecutionContext ec) {
    JFunction1<T, T> mapf =
        t -> {
          IastContext ctx = IastContext.Provider.get(AgentTracer.activeSpan());
          if (ctx != null) {
            mod.taintObjectIfTainted(ctx, t, input);
          }
          return t;
        };
    return f.map(mapf, ec);
  }
}
