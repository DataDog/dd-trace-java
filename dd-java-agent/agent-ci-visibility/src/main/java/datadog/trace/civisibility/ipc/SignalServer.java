package datadog.trace.civisibility.ipc;

import datadog.trace.util.AgentThreadFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A server for processing signals sent from children processes (forked JVMs that run tests) to the
 * parent process (build system).
 *
 * <p>Client message and server response both have a similar structure: length (4 bytes) + body [
 * signal type(1 byte) + payload ]
 */
public class SignalServer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SignalServer.class);
  private static final int DEFAULT_BUFFER_CAPACITY = 1024;

  private Selector selector;
  private ServerSocketChannel serverSocketChannel;
  private Thread signalServerThread;

  private final int port;
  private final String address;
  private final Map<SignalType, Function<Signal, SignalResponse>> signalHandlers =
      new EnumMap<>(SignalType.class);

  public SignalServer() {
    this("127.0.0.1", 0);
  }

  public SignalServer(String address, int port) {
    this.port = port;
    this.address = address;
  }

  public synchronized void start() {
    if (serverSocketChannel == null) {
      try {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);

        serverSocketChannel.bind(new InetSocketAddress(address, port));

        selector = Selector.open();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

      } catch (IOException e) {
        LOGGER.error("Error while starting signal server", e);
        return;
      }

      SignalServerRunnable signalServerRunnable =
          new SignalServerRunnable(selector, DEFAULT_BUFFER_CAPACITY, signalHandlers);
      signalServerThread =
          AgentThreadFactory.newAgentThread(
              AgentThreadFactory.AgentThread.CI_SIGNAL_SERVER, signalServerRunnable);
      signalServerThread.start();
    }
  }

  public synchronized InetSocketAddress getAddress() {
    if (serverSocketChannel == null) {
      throw new IllegalStateException("Server not started");
    }

    SocketAddress localAddress;
    try {
      localAddress = serverSocketChannel.getLocalAddress();
    } catch (IOException e) {
      LOGGER.error("Error while getting signal server address", e);
      return null;
    }

    if (localAddress instanceof InetSocketAddress) {
      return (InetSocketAddress) localAddress;

    } else {
      LOGGER.error(
          "Got unexpected address from the signal server: {}. "
              + "Signal server will not be started",
          localAddress);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  public synchronized <T extends Signal> void registerSignalHandler(
      SignalType type, Function<T, SignalResponse> handler) {
    if (serverSocketChannel != null) {
      throw new IllegalStateException("Cannot register a signal handler after server has started");
    }
    signalHandlers.put(type, (Function<Signal, SignalResponse>) handler);
  }

  public synchronized void stop() {
    if (signalServerThread != null) {
      signalServerThread.interrupt();
      try {
        signalServerThread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    try {
      if (selector != null) {
        selector.close();
      }
    } catch (IOException e) {
      LOGGER.error("Error while closing signal server selector", e);
    }

    try {
      if (serverSocketChannel != null) {
        serverSocketChannel.close();
      }
    } catch (IOException e) {
      LOGGER.error("Error while closing signal server socket channel", e);
    }
  }
}
