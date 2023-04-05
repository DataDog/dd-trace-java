package datadog.trace.bootstrap.instrumentation.sslsocket;

import datadog.trace.bootstrap.instrumentation.usm.UsmConnection;
import datadog.trace.bootstrap.instrumentation.usm.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessage;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessageFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import javax.annotation.Nonnull;
import javax.net.ssl.SSLSocket;

public class UsmFilterInputStream extends FilterInputStream {
  private final SSLSocket socket;

  /**
   * Creates an input stream filter built on top of the specified underlying input stream. This will
   * send back the buffer passed to the `read` method to system-probe.
   */
  public UsmFilterInputStream(InputStream in, SSLSocket socket) {
    super(in);

    this.socket = socket;
  }

  @Override
  public int read(@Nonnull byte[] b, int off, int len) throws IOException {
    int bytesRead = super.read(b, off, len);
    boolean isIPv6 = socket.getLocalAddress() instanceof Inet6Address;
    UsmConnection connection =
        new UsmConnection(
            socket.getLocalAddress(),
            socket.getLocalPort(),
            socket.getInetAddress(),
            socket.getPort(),
            isIPv6);
    UsmMessage message = UsmMessageFactory.Supplier.getRequestMessage(connection, b, off, len);
    UsmExtractor.Supplier.send(message);
    return bytesRead;
  }
}
