package datadog.trace.bootstrap.instrumentation.usm;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public interface UsmMessageFactory {
  UsmMessage getCloseMessage(UsmConnection connection);

  UsmMessage getRequestMessage(UsmConnection connection, byte[] buffer, int bufferOffset, int len);

  abstract class Supplier {
    private static volatile UsmMessageFactory SUPPLIER;

    public static UsmMessage getCloseMessage(UsmConnection connection) {
      return SUPPLIER.getCloseMessage(connection);
    }

    public static UsmMessage getRequestMessage(
        UsmConnection connection, byte[] buffer, int bufferOffset, int len) {
      return SUPPLIER.getRequestMessage(connection, buffer, bufferOffset, len);
    }

    @SuppressFBWarnings(
        value = "USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION",
        justification = "Agent-internal holder; Class lock does not escape to application code")
    public static synchronized void registerIfAbsent(UsmMessageFactory supplier) {
      if (null == SUPPLIER) {
        SUPPLIER = supplier;
      }
    }
  }
}
