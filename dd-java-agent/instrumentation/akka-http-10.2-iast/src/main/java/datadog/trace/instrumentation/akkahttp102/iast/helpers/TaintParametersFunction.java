package datadog.trace.instrumentation.akkahttp102.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import scala.Function1;
import scala.Option;
import scala.Tuple1;
import scala.collection.Iterable;
import scala.collection.Iterator;

public class TaintParametersFunction<T> implements Function1<Tuple1<T>, Tuple1<T>> {
  private final String paramName;

  public TaintParametersFunction(String paramName) {
    this.paramName = paramName;
  }

  @Override
  public Tuple1<T> apply(Tuple1<T> v1) {
    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null) {
      return v1;
    }

    Object value = v1._1();
    if (value instanceof Option) {
      Option<?> option = (Option<?>) value;
      if (option.isEmpty()) {
        return v1;
      }
      value = option.get();
    }

    if (value instanceof Iterable) {
      Iterator iterator = ((Iterable) value).iterator();
      while (iterator.hasNext()) {
        Object o = iterator.next();
        if (o instanceof String) {
          mod.taint(SourceTypes.REQUEST_PARAMETER_VALUE, paramName, (String) o);
        }
      }
    } else if (value instanceof String) {
      mod.taint(SourceTypes.REQUEST_PARAMETER_VALUE, paramName, (String) value);
    }

    return v1;
  }
}
