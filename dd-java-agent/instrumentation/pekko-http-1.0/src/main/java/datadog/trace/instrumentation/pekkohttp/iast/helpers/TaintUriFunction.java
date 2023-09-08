package datadog.trace.instrumentation.pekkohttp.iast.helpers;

import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.propagation.PropagationModule;
import org.apache.pekko.http.scaladsl.model.Uri;
import scala.Tuple1;
import scala.compat.java8.JFunction1;

public class TaintUriFunction implements JFunction1<Tuple1<Uri>, Tuple1<Uri>> {
  public static final TaintUriFunction INSTANCE = new TaintUriFunction();

  @Override
  public Tuple1<Uri> apply(Tuple1<Uri> v1) {
    Uri uri = v1._1();

    PropagationModule mod = InstrumentationBridge.PROPAGATION;
    if (mod == null || !(uri instanceof Taintable)) {
      return v1;
    }
    mod.taintObject(SourceTypes.REQUEST_QUERY, uri);

    return v1;
  }
}
