package datadog.trace.bootstrap.instrumentation.sslsocket;

import datadog.trace.bootstrap.instrumentation.usm.UsmConnection;
import datadog.trace.bootstrap.instrumentation.usm.UsmExtractor;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessage;
import datadog.trace.bootstrap.instrumentation.usm.UsmMessageFactory;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import javax.annotation.Nonnull;
import javax.net.ssl.SSLSocket;

public class UsmFilterOutputStream extends FilterOutputStream {
  private final SSLSocket socket;

  /**
   * Creates an output stream filter built on top of the specified underlying output stream. This
   * will send back the buffer passed to the `write` method to system-probe.
   */
  public UsmFilterOutputStream(OutputStream out, SSLSocket socket) {
    super(out);

    this.socket = socket;
  }

  @Override
  public void write(@Nonnull byte[] b, int off, int len) throws IOException {
    boolean isIPv6 = this.socket.getLocalAddress() instanceof Inet6Address;
    UsmConnection connection =
        new UsmConnection(
            socket.getLocalAddress(),
            socket.getLocalPort(),
            socket.getInetAddress(),
            socket.getPort(),
            isIPv6);
    UsmMessage message = UsmMessageFactory.Supplier.getRequestMessage(connection, b, off, len);
    UsmExtractor.Supplier.send(message);
    super.write(b, off, len);
  }
}
