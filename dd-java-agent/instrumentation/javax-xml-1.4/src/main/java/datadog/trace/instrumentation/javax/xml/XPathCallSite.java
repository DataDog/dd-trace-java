package datadog.trace.instrumentation.javax.xml;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.XPathInjectionModule;
import javax.annotation.Nullable;
import javax.xml.namespace.QName;
import org.xml.sax.InputSource;

@Sink(VulnerabilityTypes.XPATH_INJECTION)
@CallSite(spi = IastCallSites.class)
public class XPathCallSite {

  @CallSite.Before(
      "javax.xml.xpath.XPathExpression javax.xml.xpath.XPath.compile(java.lang.String)")
  public static void beforeCompile(@CallSite.Argument @Nullable final String expression) {
    onExpression(expression);
  }

  @CallSite.Before(
      "java.lang.String javax.xml.xpath.XPath.evaluate(java.lang.String, org.xml.sax.InputSource)")
  public static void beforeEvaluate(
      @CallSite.Argument @Nullable final String expression,
      @CallSite.Argument @Nullable final InputSource source) {
    onExpression(expression);
  }

  @CallSite.Before(
      "java.lang.Object javax.xml.xpath.XPath.evaluate(java.lang.String, org.xml.sax.InputSource, javax.xml.namespace.QName)")
  public static void beforeEvaluate(
      @CallSite.Argument @Nullable final String expression,
      @CallSite.Argument @Nullable final InputSource source,
      @CallSite.Argument @Nullable final QName returnType) {
    onExpression(expression);
  }

  @CallSite.Before(
      "java.lang.String javax.xml.xpath.XPath.evaluate(java.lang.String, java.lang.Object)")
  public static void beforeEvaluate(
      @CallSite.Argument @Nullable final String expression,
      @CallSite.Argument @Nullable final Object item) {
    onExpression(expression);
  }

  @CallSite.Before(
      "java.lang.Object javax.xml.xpath.XPath.evaluate(java.lang.String, java.lang.Object, javax.xml.namespace.QName)")
  public static void beforeEvaluate(
      @CallSite.Argument @Nullable final String expression,
      @CallSite.Argument @Nullable final Object item,
      @CallSite.Argument @Nullable final QName returnType) {
    onExpression(expression);
  }

  private static void onExpression(@Nullable final String expression) {
    final XPathInjectionModule module = InstrumentationBridge.XPATH_INJECTION;
    if (module != null) {
      try {
        module.onExpression(expression);
      } catch (final Throwable e) {
        module.onUnexpectedException("onExpression threw", e);
      }
    }
  }
}
