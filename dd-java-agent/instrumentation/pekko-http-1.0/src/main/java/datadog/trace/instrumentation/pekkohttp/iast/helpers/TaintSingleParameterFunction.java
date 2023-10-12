package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import scala.Option;
import scala.Tuple1;
import scala.collection.Iterable;
import scala.collection.Iterator;
import scala.compat.java8.JFunction1;

public class TaintSingleParameterFunction<Magnet>
    implements JFunction1<Tuple1<Object>, Tuple1<Object>> {
  private final String paramName;

  public TaintSingleParameterFunction(Magnet pmag) throws Exception {
    Field value$1 = pmag.getClass().getDeclaredField("value$1");
    value$1.setAccessible(true);
    Object nameReceptacle = value$1.get(pmag);
    Method nameMeth = nameReceptacle.getClass().getMethod("name");
    paramName = (String) nameMeth.invoke(nameReceptacle);
  }

  @Override
  public Tuple1<Object> apply(Tuple1<Object> v1) {
    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null || v1 == null) {
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
