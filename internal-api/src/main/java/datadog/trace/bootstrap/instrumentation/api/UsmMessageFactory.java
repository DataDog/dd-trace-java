package datadog.trace.bootstrap.instrumentation.api;

import sun.security.ssl.SSLSocketImpl;

public interface UsmMessageFactory {
  UsmMessage getCloseMessage(SSLSocketImpl socket);

  UsmMessage getRequestMessage(SSLSocketImpl socket, byte[] buffer, int bufferOffset, int len);

  abstract class Supplier {
    private static volatile UsmMessageFactory SUPPLIER;

    public static UsmMessage getCloseMessage(SSLSocketImpl socket) {
      return SUPPLIER.getCloseMessage(socket);
    }

    public static UsmMessage getRequestMessage(SSLSocketImpl socket, byte[] buffer, int bufferOffset, int len) {
      return SUPPLIER.getRequestMessage(socket, buffer, bufferOffset, len);
    }

    public static synchronized void registerIfAbsent(UsmMessageFactory supplier) {
      if (null == SUPPLIER) {
        SUPPLIER = supplier;
      }
    }
  }
}
