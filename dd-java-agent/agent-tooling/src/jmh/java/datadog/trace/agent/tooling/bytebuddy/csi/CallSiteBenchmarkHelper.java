package datadog.trace.agent.tooling.bytebuddy.csi;

import javax.servlet.ServletRequest;
import net.bytebuddy.asm.Advice;

public class CallSiteBenchmarkHelper {

  @Advice.OnMethodExit
  public static void adviceCallee(@Advice.Return(readOnly = false) String result) {
    final String currentValue = result;
    result = currentValue + " [Transformed]";
  }

  public static String adviceCallSite(final ServletRequest request, final String parameter) {
    return request.getParameter(parameter) + " [Transformed]";
  }
}
