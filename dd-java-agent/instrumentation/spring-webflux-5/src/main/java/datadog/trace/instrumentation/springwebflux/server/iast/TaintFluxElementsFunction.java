package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.function.Function;

public class TaintFluxElementsFunction<T> implements Function<T, T> {
  final PropagationModule propagation;

  public TaintFluxElementsFunction(PropagationModule propagationModule) {
    this.propagation = propagationModule;
  }

  @Override
  public T apply(T t) {
    propagation.taintObject(SourceTypes.REQUEST_BODY, t);
    return t;
  }
}
