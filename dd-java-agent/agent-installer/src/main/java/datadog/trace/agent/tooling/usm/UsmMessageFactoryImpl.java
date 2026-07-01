package datadog.trace.agent.tooling.usm;

import datadog.trace.agent.tooling.usm.UsmMessageImpl.CloseConnectionUsmMessage;
import datadog.trace.agent.tooling.usm.UsmMessageImpl.RequestUsmMessage;
import datadog.trace.bootstrap.instrumentation.usm.UsmConnection;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessage;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessageFactory;

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
