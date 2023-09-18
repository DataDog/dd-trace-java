package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.api.iast.source.WebModule;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import scala.Tuple1;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.immutable.Seq;
import scala.compat.java8.JFunction1;

public class TaintSeqFunction
    implements JFunction1<
        Tuple1<Seq<Tuple2<String, String>>>, Tuple1<Seq<Tuple2<String, String>>>> {
  public static final TaintSeqFunction INSTANCE = new TaintSeqFunction();

  @Override
  public Tuple1<Seq<Tuple2<String, String>>> apply(Tuple1<Seq<Tuple2<String, String>>> v1) {
    Seq<Tuple2<String, String>> seq = v1._1;

    WebModule web = InstrumentationBridge.WEB;
    PropagationModule prop = InstrumentationBridge.PROPAGATION;
    if (web == null || prop == null || seq == null) {
      return v1;
    }

    Iterator<Tuple2<String, String>> iterator = seq.iterator();
    Set<String> seenKeys = Collections.newSetFromMap(new IdentityHashMap<String, Boolean>());
    while (iterator.hasNext()) {
      Tuple2<String, String> t = iterator.next();
      String name = t._1();
      String value = t._2();
      if (seenKeys.add(name)) {
        web.onParameterNames(Collections.singleton(name));
      }
      prop.taint(SourceTypes.REQUEST_PARAMETER_VALUE, name, value);
    }

    return v1;
  }
}
