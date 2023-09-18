package datadog.trace.instrumentation.pekkohttp.iast;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.Taintable;
import datadog.trace.api.iast.source.WebModule;
import java.util.Collections;
import org.apache.pekko.http.javadsl.model.HttpHeader;

/**
 * Detects when a header name is directly called from user code. This uses call site instrumentation
 * because there are many calls to {@link HttpHeader#name()} inside pekko-http code that we don't
 * care about.
 */
@Source(value = SourceTypes.REQUEST_HEADER_NAME)
@CallSite(spi = IastCallSites.class)
public class HeaderNameCallSite {

  @CallSite.After("java.lang.String org.apache.pekko.http.javadsl.model.HttpHeader.name()")
  @CallSite.After(
      "java.lang.String org.apache.pekko.http.scaladsl.model.HttpHeader.name()") // subtype of the
  // first
  public static String after(@CallSite.This HttpHeader header, @CallSite.Return String result) {
    WebModule module = InstrumentationBridge.WEB;
    if (module == null) {
      return result;
    }
    try {
      if (header instanceof Taintable && ((Taintable) header).$DD$isTainted()) {
        module.onHeaderNames(Collections.singletonList(result));
      }
      return result;
    } catch (final Throwable e) {
      module.onUnexpectedException("onHeaderNames threw", e);
      return result;
    }
  }
}
