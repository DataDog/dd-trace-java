package datadog.common.socket;

import static java.util.concurrent.TimeUnit.MINUTES;

import datadog.environment.JavaVirtualMachine;
import datadog.logging.RatelimitedLogger;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import jnr.unixsocket.UnixSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Impersonate TCP-style SocketFactory over UNIX domain sockets.
 *
 * <p>Copied from <a
 * href="https://github.com/square/okhttp/blob/master/samples/unixdomainsockets/src/main/java/okhttp3/unixdomainsockets/UnixDomainSocketFactory.java">okHttp
 * examples</a>.
 */
public final class UnixDomainSocketFactory extends SocketFactory {
  private static final Logger log = LoggerFactory.getLogger(UnixDomainSocketFactory.class);

  private static final boolean JDK_SUPPORTS_UDS = JavaVirtualMachine.isJavaVersionAtLeast(16);

  private final RatelimitedLogger rlLog = new RatelimitedLogger(log, 5, MINUTES);

  private final File path;
  private final boolean useJdkUdsSocket;
  private final boolean agentConfiguredUsingDefault;

  public UnixDomainSocketFactory(
      final File path, boolean isJdkSocketEnabled, boolean agentConfiguredUsingDefault) {
    this.path = path;
    this.useJdkUdsSocket = isJdkSocketEnabled && JavaVirtualMachine.isJavaVersionAtLeast(16);
    this.agentConfiguredUsingDefault = agentConfiguredUsingDefault;
  }

  @Override
  public Socket createSocket() throws IOException {
    try {
      if (this.useJdkUdsSocket) {
        try {
          return new TunnelingJdkSocket(this.path.toPath());
        } catch (Throwable ignore) {
          // fall back to jnr-unixsocket library
        }
      }
      return new TunnelingUnixSocket(this.path, UnixSocketChannel.open());
    } catch (Throwable e) {
      if (this.agentConfiguredUsingDefault) {
        // fall back to port if we previously auto-discovered this socket file
        if (log.isDebugEnabled()) {
          rlLog.warn("Problem opening {}, using port instead", path, e);
        } else {
          rlLog.warn("Problem opening {}, using port instead: " + e, path);
        }
        return getDefault().createSocket();
      }
      throw e;
    }
  }

  @Override
  public Socket createSocket(final String host, final int port) throws IOException {
    final Socket result = createSocket();
    result.connect(new InetSocketAddress(host, port));
    return result;
  }

  @Override
  public Socket createSocket(
      final String host, final int port, final InetAddress localHost, final int localPort)
      throws IOException {
    return createSocket(host, port);
  }

  @Override
  public Socket createSocket(final InetAddress host, final int port) throws IOException {
    final Socket result = createSocket();
    result.connect(new InetSocketAddress(host, port));
    return result;
  }

  @Override
  public Socket createSocket(
      final InetAddress host, final int port, final InetAddress localAddress, final int localPort)
      throws IOException {
    return createSocket(host, port);
  }
}
