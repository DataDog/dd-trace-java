package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
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

    WebModule mod = InstrumentationBridge.WEB;
    if (mod == null || m == null) {
      return v1;
    }

    java.util.List<String> keysAsCollection = ScalaToJava.keySetAsCollection(m);
    mod.onParameterNames(keysAsCollection);

    Iterator<Tuple2<String, List<String>>> entriesIterator = m.iterator();
    while (entriesIterator.hasNext()) {
      Tuple2<String, List<String>> e = entriesIterator.next();
      List<String> values = e._2();
      mod.onParameterValues(e._1(), ScalaToJava.listAsList(values));
    }

    return v1;
  }
}
