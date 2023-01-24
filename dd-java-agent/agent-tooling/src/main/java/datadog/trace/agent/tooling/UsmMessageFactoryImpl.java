package datadog.trace.agent.tooling;

import sun.security.ssl.SSLSocketImpl;
import datadog.trace.agent.tooling.UsmMessageImpl.CloseConnectionUsmMessage;
import datadog.trace.agent.tooling.UsmMessageImpl.RequestUsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory;

public class UsmMessageFactoryImpl implements UsmMessageFactory {
  @Override
  public UsmMessage getCloseMessage(SSLSocketImpl socket) {
    return new CloseConnectionUsmMessage(socket);
  }

  @Override
  public UsmMessage getRequestMessage(SSLSocketImpl socket, byte[] buffer, int bufferOffset, int len) {
    return new RequestUsmMessage(socket, buffer, bufferOffset, len);
  }

  public static void registerAsSupplier() {
    UsmMessageFactory.Supplier.registerIfAbsent(
        new UsmMessageFactoryImpl());
  }
}
