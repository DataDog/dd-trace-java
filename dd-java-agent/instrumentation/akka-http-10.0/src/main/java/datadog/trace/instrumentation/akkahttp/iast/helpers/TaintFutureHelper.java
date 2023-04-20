package datadog.trace.instrumentation.akkahttp.iast.helpers;

import datadog.trace.api.iast.propagation.PropagationModule;
import scala.compat.java8.JFunction1;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

public class TaintFutureHelper {
  public static <T> Future<T> wrapFuture(
      Future<T> f, Object input, PropagationModule mod, ExecutionContext ec) {
    JFunction1<T, T> mapf =
        t -> {
          mod.taintIfInputIsTainted(t, input);
          return t;
        };
    return f.map(mapf, ec);
  }
}
