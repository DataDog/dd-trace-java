package datadog.trace.bootstrap.instrumentation.api;

import static datadog.trace.util.IntStringUtils.parseNonNegativeInt;

import javax.annotation.Nullable;

/**
 * Splits an HTTP {@code Host} header value ({@code host}, {@code host:port}, {@code [ipv6]} or
 * {@code [ipv6]:port}) without throwing. The value comes from the client, so it may be malformed.
 */
public final class HostHeader {

  private HostHeader() {}

  /**
   * @return the index of the ':' separating the host from the port, or {@code -1} if there is no
   *     port; a ':' inside a bracketed IPv6 literal is not a separator
   */
  public static int portSeparator(@Nullable String hostHeader) {
    if (hostHeader == null) {
      return -1;
    }
    final int colon = hostHeader.lastIndexOf(':');
    return colon > hostHeader.lastIndexOf(']') ? colon : -1;
  }

  /**
   * @return the host part, or the whole value if there is no port
   */
  @Nullable
  public static String host(@Nullable String hostHeader) {
    final int separator = portSeparator(hostHeader);
    return separator == -1 ? hostHeader : hostHeader.substring(0, separator);
  }

  /**
   * @return the port, or {@code -1} if there is no port or it is not a valid number
   */
  public static int port(@Nullable String hostHeader) {
    final int separator = portSeparator(hostHeader);
    if (separator == -1) {
      return -1;
    }
    return parseNonNegativeInt(hostHeader, separator + 1, hostHeader.length() - separator - 1);
  }
}
