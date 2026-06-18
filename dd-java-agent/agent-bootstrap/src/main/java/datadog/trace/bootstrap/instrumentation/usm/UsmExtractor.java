package datadog.trace.bootstrap.instrumentation.usm;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public interface UsmExtractor {
  void send(UsmMessage message);

  abstract class Supplier {
    private static volatile UsmExtractor SUPPLIER;

    public static void send(UsmMessage message) {
      SUPPLIER.send(message);
    }

    // SpotBugs USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION: false positive, can be suppressed.
    // UsmExtractor.Supplier is an agent-internal holder loaded in the isolated agent context; its
    // Class lock does not escape to application code, and the lock only guards the private SUPPLIER
    // field.
    @SuppressFBWarnings(
        value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
        justification = "Agent-internal holder; Class lock does not escape to application code")
    public static synchronized void registerIfAbsent(UsmExtractor supplier) {
      if (SUPPLIER == null) {
        SUPPLIER = supplier;
      }
    }
  }
}
