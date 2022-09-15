package datadog.trace.instrumentation.csi.iast;

import datadog.trace.agent.tooling.csi.CallSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@CallSite(spi = IastAdvice.class)
public class StringConcatFactoryCallSite {

  private static final Logger LOG = LoggerFactory.getLogger(StringConcatFactoryCallSite.class);

  @CallSite.After(
      "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])")
  public static String afterStringPlus(
      @CallSite.AllArguments final Object[] arguments, @CallSite.Return final String result) {
    LOG.debug("After string plus (invoke dynamic)");
    return result;
  }
}
