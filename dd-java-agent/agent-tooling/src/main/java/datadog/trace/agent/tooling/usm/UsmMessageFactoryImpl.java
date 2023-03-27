package datadog.trace.agent.tooling.usm;

import datadog.trace.agent.tooling.usm.UsmMessageImpl.CloseConnectionUsmMessage;
import datadog.trace.agent.tooling.usm.UsmMessageImpl.RequestUsmMessage;
import datadog.trace.agent.tooling.usm.UsmMessageImpl.HostUsmMessage;
import datadog.trace.agent.tooling.usm.UsmMessageImpl.PlainUsmMessage;
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

  @Override
  public UsmMessage getPlainMessage(UsmConnection connection, String hostname, byte[] buffer, int bufferOffset, int len) {
    return new PlainUsmMessage(connection,hostname,buffer,bufferOffset,len);
  }

  @Override
  public UsmMessage getHostMessage(UsmConnection connection, String hostName) {
    return new HostUsmMessage(connection, hostName);
  }

  public static void registerAsSupplier() {
    UsmMessageFactory.Supplier.registerIfAbsent(new UsmMessageFactoryImpl());
  }
}
