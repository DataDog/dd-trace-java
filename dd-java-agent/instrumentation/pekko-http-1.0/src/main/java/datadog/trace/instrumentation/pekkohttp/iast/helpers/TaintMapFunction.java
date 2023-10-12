package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.source.WebModule;
import scala.Tuple1;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.immutable.Map;
import scala.compat.java8.JFunction1;

public class TaintMapFunction
    implements JFunction1<Tuple1<Map<String, String>>, Tuple1<Map<String, String>>> {
  public static final TaintMapFunction INSTANCE = new TaintMapFunction();

  @Override
  public Tuple1<Map<String, String>> apply(Tuple1<Map<String, String>> v1) {
    Map<String, String> m = v1._1;

    PropagationModule prop = InstrumentationBridge.PROPAGATION;
    WebModule web = InstrumentationBridge.WEB;
    if (web == null || prop == null || m == null) {
      return v1;
    }

    java.util.List<String> keysAsCollection = ScalaToJava.keySetAsCollection(m);
    web.onParameterNames(keysAsCollection);

    Iterator<Tuple2<String, String>> iterator = m.iterator();
    while (iterator.hasNext()) {
      Tuple2<String, String> e = iterator.next();
      prop.taint(SourceTypes.REQUEST_PARAMETER_VALUE, e._1(), e._2());
    }

    return v1;
  }
}
