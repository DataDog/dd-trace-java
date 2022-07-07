package datadog.common.socket;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import javax.net.SocketFactory;

public class NamedPipeSocketFactory extends SocketFactory {
  private static final String NAMED_PIPE_PREFIX = "\\\\.\\pipe\\";
  private static final Map<File, NamedPipeSocket> fileToSocket = new HashMap<>();
  private final File pipe;

  public NamedPipeSocketFactory(String pipeName) {
    String pipeNameWithPrefix =
        pipeName.startsWith(NAMED_PIPE_PREFIX) ? pipeName : NAMED_PIPE_PREFIX + pipeName;
    this.pipe = new File(pipeNameWithPrefix);
  }

  @Override
  public Socket createSocket() throws IOException {
    // Getting a new socket is rare enough that simple synchronization is sufficient
    synchronized (fileToSocket) {
      NamedPipeSocket socket = fileToSocket.get(pipe);

      if (socket == null || socket.isClosed()) {
        socket = new NamedPipeSocket(pipe);
        fileToSocket.put(pipe, socket);
      }

      return socket;
    }
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    return createSocket();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return createSocket();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return createSocket();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    return createSocket();
  }
}
