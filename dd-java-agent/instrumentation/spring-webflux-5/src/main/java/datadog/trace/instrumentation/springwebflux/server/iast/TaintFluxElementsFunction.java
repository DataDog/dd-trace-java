package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import java.util.function.Function;

public class TaintFluxElementsFunction<T> implements Function<T, T> {

  final TaintedObjects to;
  final PropagationModule propagation;

  public TaintFluxElementsFunction(TaintedObjects to, PropagationModule propagationModule) {
    this.to = to;
    this.propagation = propagationModule;
  }

  @Override
  public T apply(T t) {
    propagation.taintObject(to, t, SourceTypes.REQUEST_BODY);
    return t;
  }
}
