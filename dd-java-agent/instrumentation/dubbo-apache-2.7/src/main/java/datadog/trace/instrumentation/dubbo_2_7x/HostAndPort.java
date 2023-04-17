package datadog.trace.instrumentation.dubbo_2_7x;

/**
 * @Description
 * @Author liurui
 * @Date 2023/3/29 17:23
 */
public class HostAndPort {
  public static final int NO_PORT = -1;

  public static int getValidPortOrNoPort(int port) {
    if (!isValidPort(port)) {
      return NO_PORT;
    }
    return port;
  }

  public static int getPortOrNoPort(int port) {
    if (port < 0) {
      return HostAndPort.NO_PORT;
    }
    return port;
  }

  public static boolean isValidPort(int port) {
    return port >= 0 && port <= 65535;
  }

  public static String toHostAndPortString(String host, int port) {
    return toHostAndPortString(host, port, NO_PORT);
  }

  /**
   * This API does not verification for input args.
   */
  public static String toHostAndPortString(String host, int port, int noPort) {
    // don't validation hostName
    // don't validation port range
    if (noPort == port) {
      return host;
    }
    final int hostLength = host == null ? 0 : host.length();
    final StringBuilder builder = new StringBuilder(hostLength + 6);
    builder.append(host);
    builder.append(':');
    builder.append(port);
    return builder.toString();
  }

}
