package datadog.trace.bootstrap.instrumentation.sslsocket;

import datadog.trace.bootstrap.instrumentation.usm.Connection;
import datadog.trace.bootstrap.instrumentation.usm.Extractor;
import datadog.trace.bootstrap.instrumentation.usm.MessageEncoder;
import datadog.trace.bootstrap.instrumentation.usm.Payload;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.nio.Buffer;
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
    Connection connection =
        new Connection(
            socket.getLocalAddress(),
            socket.getLocalPort(),
            socket.getInetAddress(),
            socket.getPort(),
            isIPv6);
    Payload payload = new Payload(b, off, len);
    Buffer message = MessageEncoder.encode(MessageEncoder.SYNCHRONOUS_PAYLOAD, connection, payload);
    Extractor.Supplier.send(message);
    super.write(b, off, len);
  }
}
