package probe;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.Trace;
import weblogic.diagnostics.context.DiagnosticContext;
import weblogic.diagnostics.context.DiagnosticContextFactory;
import weblogic.workarea.PrimitiveContextFactory;
import weblogic.workarea.PropagationMode;
import weblogic.workarea.WorkContextHelper;
import weblogic.workarea.WorkContextMap;

public class Caller {
  private static final String KEY = "datadog.trace.t3.v1";

  public static void main(String[] args) throws Exception {
    Echo server = Lookup.echo("t3://127.0.0.1:7001");
    server.prepare("t3://127.0.0.1:7002");
    nested(server);
    failure(server);
    if (!"0".equals(server.untraced())) {
      throw new AssertionError("untraced call inherited context");
    }
    WorkContextMap map = workArea();
    map.put(
        KEY,
        PrimitiveContextFactory.create("invalid!"),
        PropagationMode.RMI | PropagationMode.ONEWAY);
    try {
      if (!"0".equals(server.untraced())) {
        throw new AssertionError("malformed carrier became an active trace");
      }
    } finally {
      map.remove(KEY);
    }
    System.out.println("T3 probe completed");
  }

  @Trace(operationName = "probe.caller")
  public static void nested(Echo server) {
    String expected = CorrelationIdentifier.getTraceId();
    if ("0".equals(expected) || !expected.equals(server.untraced())) {
      throw new AssertionError("trace context unavailable in EJB without an application span");
    }
    DiagnosticContext diagnostic = DiagnosticContextFactory.findOrCreateDiagnosticContext(true);
    String[] observed = server.nested().split("\\|", -1);
    if ("0".equals(expected) || !expected.equals(observed[0])) {
      throw new AssertionError("trace IDs differ: " + expected + " / " + observed[0]);
    }
    String ecid = diagnostic.getContextId();
    if (!ecid.equals(observed[1]) || !ecid.equals(observed[3]) || observed[2].equals(observed[4])) {
      throw new AssertionError("unexpected ECID/RID: " + ecid + " / " + String.join("|", observed));
    }
    System.out.println("Oracle ECID preserved and RID advanced across both T3 hops");
    assertCarrierCleared();
  }

  @Trace(operationName = "probe.failure")
  public static void failure(Echo server) {
    try {
      server.fail(CorrelationIdentifier.getTraceId());
      throw new AssertionError("expected EJB failure");
    } catch (RuntimeException expected) {
      Throwable cause = expected;
      while (cause != null && !"probe failure".equals(cause.getMessage())) {
        cause = cause.getCause();
      }
      if (cause == null && !expected.toString().contains("probe failure")) {
        throw expected;
      }
    }
    assertCarrierCleared();
  }

  private static void assertCarrierCleared() {
    if (workArea().get(KEY) != null) {
      throw new AssertionError("client WorkArea retained trace entry");
    }
  }

  private static WorkContextMap workArea() {
    return WorkContextHelper.getWorkContextHelper().getWorkContextMap();
  }
}
