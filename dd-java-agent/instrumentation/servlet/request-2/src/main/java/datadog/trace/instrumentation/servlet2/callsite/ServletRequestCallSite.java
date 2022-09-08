package datadog.trace.instrumentation.servlet2.callsite;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastAdvice;
import java.util.Enumeration;
import javax.servlet.ServletRequest;
import net.bytebuddy.asm.Advice;

@CallSite(spi = IastAdvice.class)
public class ServletRequestCallSite {

  @CallSite.Around("java.lang.String javax.servlet.ServletRequest.getParameter(java.lang.String)")
  public static String aroundGetParameter(
      @Advice.This final ServletRequest self, @Advice.Argument(0) final String paramName) {
    String retValue = self.getParameter(paramName);
    // taint String retValue here
    MockTainter.taintObject(retValue);
    return retValue;
  }

  @CallSite.Around("java.util.Enumeration javax.servlet.ServletRequest.getParameterNames()")
  public static Enumeration aroundGetParameterNames(@Advice.This final ServletRequest self) {
    Enumeration enumeration = self.getParameterNames();
    while (enumeration.hasMoreElements()) {
      MockTainter.taintObject(enumeration.nextElement());
    }
    return self.getParameterNames();
  }

  @CallSite.Around(
      "java.lang.String[] javax.servlet.ServletRequest.getParameterValues(java.lang.String)")
  public static String[] aroundGetParameterValues(
      @Advice.This final ServletRequest self, @Advice.Argument(0) final String paramName) {
    String[] parameterValues = self.getParameterValues(paramName);
    if (null != parameterValues) {
      for (String value : parameterValues) {
        MockTainter.taintObject(value);
      }
    }
    return parameterValues;
  }
}
