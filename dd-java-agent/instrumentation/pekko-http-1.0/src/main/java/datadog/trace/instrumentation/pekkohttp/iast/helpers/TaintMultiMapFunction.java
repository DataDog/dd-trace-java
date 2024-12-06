package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.taint.TaintedObjects;
import scala.Tuple1;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.immutable.List;
import scala.collection.immutable.Map;
import scala.compat.java8.JFunction1;

public class TaintMultiMapFunction
    implements JFunction1<Tuple1<Map<String, List<String>>>, Tuple1<Map<String, List<String>>>> {
  public static final TaintMultiMapFunction INSTANCE = new TaintMultiMapFunction();

  @Override
  public Tuple1<Map<String, List<String>>> apply(Tuple1<Map<String, List<String>>> v1) {
    Map<String, List<String>> m = v1._1;

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null || m == null || m.isEmpty()) {
      return v1;
    }

    final TaintedObjects to = IastContext.Provider.taintedObjects();
    if (to == null) {
      return v1;
    }
    Iterator<Tuple2<String, List<String>>> entriesIterator = m.iterator();
    while (entriesIterator.hasNext()) {
      Tuple2<String, List<String>> e = entriesIterator.next();
      final String name = e._1();
      mod.taintObject(to, name, SourceTypes.REQUEST_PARAMETER_NAME, name);
      List<String> values = e._2();
      for (final String value : ScalaToJava.listAsList(values)) {
        mod.taintObject(to, value, SourceTypes.REQUEST_PARAMETER_VALUE, name);
      }
    }

    return v1;
  }
}
