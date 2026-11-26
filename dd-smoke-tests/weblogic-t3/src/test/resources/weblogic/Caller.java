package probe;

import static weblogic.workarea.PrimitiveContextFactory.create;
import static weblogic.workarea.PropagationMode.LOCAL;
import static weblogic.workarea.PropagationMode.ONEWAY;
import static weblogic.workarea.PropagationMode.RMI;
import static weblogic.workarea.WorkContextHelper.getWorkContextHelper;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.Trace;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import weblogic.diagnostics.context.DiagnosticContext;
import weblogic.diagnostics.context.DiagnosticContextFactory;
import weblogic.workarea.StringWorkContext;
import weblogic.workarea.WorkContext;
import weblogic.workarea.WorkContextMap;
import weblogic.workarea.WorkContextOutput;

public class Caller {
  private static final String KEY = "datadog.trace.t3.v1";
  private static final int SEEDED_MODE = LOCAL | RMI | ONEWAY;

  public static void main(String[] args) throws Exception {
    Echo server = Lookup.echo("t3://127.0.0.1:7001");
    server.prepare("t3://127.0.0.1:7002");
    nested(server);
    failure(server);
    if (!"0".equals(server.untraced())) {
      throw new AssertionError("untraced call inherited context");
    }
    WorkContextMap map = workArea();
    map.put(KEY, create("invalid!"), RMI | ONEWAY);
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
  public static void nested(Echo server) throws Exception {
    String expected = CorrelationIdentifier.getTraceId();
    if ("0".equals(expected) || !expected.equals(server.untraced())) {
      throw new AssertionError("trace context unavailable in EJB without an application span");
    }
    assertCarrierCleared();
    DiagnosticContext diagnostic = DiagnosticContextFactory.findOrCreateDiagnosticContext(true);
    WorkContextMap map = workArea();
    WorkContext previous = staleCarrier();
    map.put(KEY, previous, SEEDED_MODE);
    String[] observed;
    try {
      observed = server.nested().split("\\|", -1);
      assertCarrierRestored(map, previous);
    } finally {
      map.remove(KEY);
    }
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
  public static void failure(Echo server) throws Exception {
    try {
      server.fail(CorrelationIdentifier.getTraceId());
      throw new AssertionError("expected EJB failure");
    } catch (RuntimeException expected) {
      assertFailure(expected, "probe failure");
    }
    assertCarrierCleared();

    WorkContextMap map = workArea();
    WorkContext previous = staleCarrier();
    map.put(KEY, previous, SEEDED_MODE);
    String failingKey = "probe.failing";
    String failure = "probe WorkArea write failure";
    try {
      map.put(
          failingKey,
          new StringWorkContext() {
            @Override
            public void writeContext(WorkContextOutput output) throws IOException {
              throw new IOException(failure);
            }
          },
          RMI | ONEWAY);
      try {
        server.untraced();
        throw new AssertionError("expected WorkArea serialization failure");
      } catch (RuntimeException expected) {
        assertFailure(expected, failure);
      } finally {
        map.remove(failingKey);
      }
      assertCarrierRestored(map, previous);
    } finally {
      map.remove(KEY);
    }
  }

  private static WorkContext staleCarrier() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      output.writeUTF("traceparent");
      output.writeUTF("00-00000000000000000000000000000001-0000000000000002-01");
      output.writeUTF("x-datadog-trace-id");
      output.writeUTF("1");
      output.writeUTF("x-datadog-parent-id");
      output.writeUTF("2");
    }
    return create(Base64.getEncoder().encodeToString(bytes.toByteArray()));
  }

  private static void assertCarrierRestored(WorkContextMap map, WorkContext previous) {
    if (map.get(KEY) != previous || map.getPropagationMode(KEY) != SEEDED_MODE) {
      throw new AssertionError("client WorkArea did not restore the previous trace entry");
    }
  }

  private static void assertFailure(RuntimeException error, String message) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause.toString().contains(message)) {
        return;
      }
    }
    throw error;
  }

  private static void assertCarrierCleared() {
    if (workArea().get(KEY) != null) {
      throw new AssertionError("client WorkArea retained trace entry");
    }
  }

  private static WorkContextMap workArea() {
    return getWorkContextHelper().getWorkContextMap();
  }
}
