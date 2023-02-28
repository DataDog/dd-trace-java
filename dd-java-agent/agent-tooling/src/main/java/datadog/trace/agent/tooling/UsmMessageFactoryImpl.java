package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.UsmMessageImpl.CloseConnectionUsmMessage;
import datadog.trace.agent.tooling.UsmMessageImpl.RequestUsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmConnection;
import datadog.trace.bootstrap.instrumentation.api.UsmMessage;
import datadog.trace.bootstrap.instrumentation.api.UsmMessageFactory;

public class UsmMessageFactoryImpl implements UsmMessageFactory {
  @Override
  public UsmMessage getCloseMessage(UsmConnection connection) {
    return new CloseConnectionUsmMessage(connection);
  }

  @Override
  public UsmMessage getRequestMessage(
      UsmConnection connection, byte[] buffer, int bufferOffset, int len) {
    return new RequestUsmMessage(connection, buffer, bufferOffset, len);
  }

  public static void registerAsSupplier() {
    UsmMessageFactory.Supplier.registerIfAbsent(new UsmMessageFactoryImpl());
  }
}
