package datadog.trace.bootstrap.instrumentation.decorator.http;

import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientIpAddressResolver {
  private static final Logger log = LoggerFactory.getLogger(ClientIpAddressResolver.class);

  private static final Function<String, InetAddress> plainIpAddressParser =
      new ParsePlainIpAddress();
  private static final Function<String, InetAddress> forwardedParser = new ParseForwarded();
  private static final Function<String, InetAddress> viaParser = new ParseVia();

  /**
   * Infers the IP address of the client according to our specified procedure. This method doesn't
   * throw exceptions.
   *
   * <p>In ideal circumstances, <code>ipAddrHeader</code> is specified so as to minimize the chances
   * that the ip address be spoofed.
   *
   * @param context extracted context with http headers
   * @return the inferred IP address, if any
   */
  public static InetAddress resolve(AgentSpan.Context.Extracted context) {
    try {
      return doResolve(context);
    } catch (RuntimeException rte) {
      log.warn("Unexpected exception (bug) inferring client IP address", rte);
      return null;
    }
  }

  public static InetAddress doResolve(AgentSpan.Context.Extracted context) {
    if (context == null) {
      return null;
    }
    InetAddress result;

    String customIpHeader = context.getCustomIpHeader();
    if (customIpHeader != null) {
      result = tryHeader(customIpHeader, forwardedParser);
      if (result != null) {
        return result;
      }
      result = tryHeader(customIpHeader, plainIpAddressParser);
      if (result != null) {
        return result;
      }
      // If custom header is defined but not resolved - return null
      return null;
    }

    // we don't have a set ip header to look exclusively at
    // the order of the headers is the order in the RFC

    result = tryHeader(context.getXForwardedFor(), plainIpAddressParser);
    if (result != null) {
      return result;
    }

    result = tryHeader(context.getXRealIp(), plainIpAddressParser);
    if (result != null) {
      return result;
    }

    result = tryHeader(context.getClientIp(), plainIpAddressParser);
    if (result != null) {
      return result;
    }

    result = tryHeader(context.getXForwarded(), forwardedParser);
    if (result != null) {
      return result;
    }

    result = tryHeader(context.getXClusterClientIp(), plainIpAddressParser);
    if (result != null) {
      return result;
    }

    result = tryHeader(context.getForwardedFor(), plainIpAddressParser);
    if (result != null) {
      return result;
    }

    result = tryHeader(context.getForwarded(), forwardedParser);
    if (result != null) {
      return result;
    }

    result = tryHeader(context.getVia(), viaParser);
    if (result != null) {
      return result;
    }

    return tryHeader(context.getTrueClientIp(), plainIpAddressParser);
  }

  private static InetAddress tryHeader(String headerValue, Function<String, InetAddress> parseFun) {
    if (headerValue == null || headerValue.isEmpty()) {
      return null;
    }

    return parseFun.apply(headerValue);
  }

  private static class ParseVia implements Function<String, InetAddress> {
    @Override
    public InetAddress apply(String str) {
      int pos = 0;
      int end = str.length();
      InetAddress result = null;
      do {
        int posComma = str.indexOf(',', pos);
        int endCur = posComma == -1 ? end : posComma;

        // skip initial whitespace, after a comma separating several
        // values for instance
        pos = skipWs(str, pos, endCur);
        if (pos != endCur) {
          // https://httpwg.org/specs/rfc7230.html#header.via
          // skip over protocol/version
          pos = skipNonWs(str, pos, endCur);
          pos = skipWs(str, pos, endCur);
          if (pos != endCur) {
            // we can have a trailing comment, so try find next whitespace
            endCur = skipNonWs(str, pos, endCur);

            result = parseIpAddressAndMaybePort(str.substring(pos, endCur));
            if (result != null && isIpAddrPrivate(result)) {
              result = null;
            }
          }
        }
        pos = (posComma != -1 && posComma + 1 < end) ? (posComma + 1) : -1;
      } while (result == null && pos != -1);

      return result;
    }

    private static int skipNonWs(String str, int pos, int endCur) {
      for (; pos < endCur; pos++) {
        char c = str.charAt(pos);
        if (c == ' ' || c == '\t') {
          break;
        }
      }
      return pos;
    }

    private static int skipWs(String str, int pos, int endCur) {
      for (; pos < endCur; pos++) {
        char c = str.charAt(pos);
        if (c != ' ' && c != '\t') {
          break;
        }
      }
      return pos;
    }
  }

  private static class ParsePlainIpAddress implements Function<String, InetAddress> {
    @Override
    public InetAddress apply(String str) {
      InetAddress addr;
      int pos = 0;
      int end = str.length();
      do {
        for (; pos < end && str.charAt(pos) == ' '; pos++) {}
        int posComma = str.indexOf(',', pos);
        int endCur = posComma != -1 ? posComma : end;
        addr = parseIpAddress(str.substring(pos, endCur));
        if (addr != null && isIpAddrPrivate(addr)) {
          addr = null;
        }
        pos = (posComma != -1 && posComma + 1 < end) ? (posComma + 1) : -1;
      } while (addr == null && pos != -1);
      return addr;
    }
  }

  private static class ParseForwarded implements Function<String, InetAddress> {

    enum ForwardedParseState {
      KEY,
      BEFORE_VALUE,
      VALUE_TOKEN,
      VALUE_QUOTED,
      BETWEEN,
    }

    @Override
    public InetAddress apply(String headerValue) {
      ForwardedParseState state = ForwardedParseState.BETWEEN;

      // https://datatracker.ietf.org/doc/html/rfc7239#section-4
      int pos = 0;
      int end = headerValue.length();
      // compiler requires that these two be initialized:
      int start = 0;
      boolean considerValue = false;
      while (pos < end) {
        char c = headerValue.charAt(pos);
        switch (state) {
          case BETWEEN:
            if (c == ' ' || c == ';' || c == ',') {
              break;
            }
            start = pos;
            state = ForwardedParseState.KEY;
            break;
          case KEY:
            if (c != '=') {
              break;
            }

            state = ForwardedParseState.BEFORE_VALUE;
            if (pos - start == 3) {
              String key = headerValue.substring(start, pos);
              considerValue = key.equalsIgnoreCase("for");
            } else {
              considerValue = false;
            }
            break;
          case BEFORE_VALUE:
            if (c == '"') {
              start = pos + 1;
              state = ForwardedParseState.VALUE_QUOTED;
            } else if (c == ' ' || c == ';' || c == ',') {
              // empty value
              state = ForwardedParseState.BETWEEN;
            } else {
              start = pos;
              state = ForwardedParseState.VALUE_TOKEN;
            }
            break;
          case VALUE_TOKEN:
            {
              int tokenEnd;
              if (c == ' ' || c == ';' || c == ',') {
                tokenEnd = pos;
              } else if (pos + 1 == end) {
                tokenEnd = end;
              } else {
                break;
              }

              if (considerValue) {
                InetAddress ipAddr =
                    parseIpAddressAndMaybePort(headerValue.substring(start, tokenEnd));
                if (ipAddr != null && !isIpAddrPrivate(ipAddr)) {
                  return ipAddr;
                }
              }
              state = ForwardedParseState.BETWEEN;
              break;
            }
          case VALUE_QUOTED:
            if (c == '"') {
              if (considerValue) {
                InetAddress ipAddr = parseIpAddressAndMaybePort(headerValue.substring(start, pos));
                if (ipAddr != null && !isIpAddrPrivate(ipAddr)) {
                  return ipAddr;
                }
              }
              state = ForwardedParseState.BETWEEN;
            } else if (c == '\\') {
              pos++;
            }
            break;
        }
        pos++;
      }

      return null;
    }
  }

  // flat array in groups of 4 + 4 bytes (base address of the range and mask)
  private static final byte[] PRIVATE_IPV4_RANGES = {
    (byte) 0x0A, 0, 0, 0, /**/ (byte) 0xFF, 0, 0, 0, // 10.0.0.0/8
    (byte) 0xAC, (byte) 0x10, 0, 0, /**/ (byte) 0xFF, (byte) 0xF0, 0, 0, // 172.16.0.0/12
    (byte) 0xC0, (byte) 0xA8, 0, 0, /**/ (byte) 0xFF, (byte) 0xFF, 0, 0, // 192.168.0.0/16
    (byte) 0x7F, 0, 0, 0, /**/ (byte) 0xFF, 0, 0, 0, // 127.0.0.0/8
    (byte) 0xA9, (byte) 0xFE, 0, 0, /**/ (byte) 0xFF, (byte) 0xFF, 0, 0, // 169.254.0.0/16
  };
  private static final int PRIVATE_IPV4_RANGES_SIZE = PRIVATE_IPV4_RANGES.length / (4 + 4);

  private static final byte[] PRIVATE_IPV6_RANGES = {
    // spotless:off
      // ::1/128
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
      (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
      // fe80::/10
      (byte)0xFE, (byte)0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      (byte)0xFF, (byte)0xC0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      // fc::/7
      (byte)0xFC, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      (byte)0xFE, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      //spotless:on
  };
  private static final int PRIVATE_IPV6_RANGES_SIZE = PRIVATE_IPV4_RANGES.length / (16 + 16);

  public static boolean isIpAddrPrivate(InetAddress ipAddr) {
    if (ipAddr instanceof Inet4Address) {
      byte[] addr = ipAddr.getAddress();
      for (int i = 0; i < PRIVATE_IPV4_RANGES_SIZE; i++) {
        if (matchesPrivateRange4(addr, i)) {
          return true;
        }
      }
    } else if (ipAddr instanceof Inet6Address) {
      byte[] addr = ipAddr.getAddress();
      for (int i = 0; i < PRIVATE_IPV6_RANGES_SIZE; i++) {
        if (matchesPrivateRange6(addr, i)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean matchesPrivateRange4(byte[] addr, int n) {
    byte[] maskedAddr = {
      (byte) (addr[0] & PRIVATE_IPV4_RANGES[n * 8 + 4]),
      (byte) (addr[1] & PRIVATE_IPV4_RANGES[n * 8 + 5]),
      (byte) (addr[2] & PRIVATE_IPV4_RANGES[n * 8 + 6]),
      (byte) (addr[3] & PRIVATE_IPV4_RANGES[n * 8 + 7]),
    };

    return maskedAddr[0] == PRIVATE_IPV4_RANGES[n * 8]
        && maskedAddr[1] == PRIVATE_IPV4_RANGES[n * 8 + 1]
        && maskedAddr[2] == PRIVATE_IPV4_RANGES[n * 8 + 2]
        && maskedAddr[3] == PRIVATE_IPV4_RANGES[n * 8 + 3];
  }

  private static boolean matchesPrivateRange6(byte[] addr, int n) {
    int base = n * 32;
    byte[] maskedAddr = {
      (byte) (addr[0] & PRIVATE_IPV6_RANGES[base + 16]),
      (byte) (addr[1] & PRIVATE_IPV6_RANGES[base + 17]),
      (byte) (addr[2] & PRIVATE_IPV6_RANGES[base + 18]),
      (byte) (addr[3] & PRIVATE_IPV6_RANGES[base + 19]),
      (byte) (addr[4] & PRIVATE_IPV6_RANGES[base + 20]),
      (byte) (addr[5] & PRIVATE_IPV6_RANGES[base + 21]),
      (byte) (addr[6] & PRIVATE_IPV6_RANGES[base + 22]),
      (byte) (addr[7] & PRIVATE_IPV6_RANGES[base + 23]),
      (byte) (addr[8] & PRIVATE_IPV6_RANGES[base + 24]),
      (byte) (addr[9] & PRIVATE_IPV6_RANGES[base + 25]),
      (byte) (addr[10] & PRIVATE_IPV6_RANGES[base + 26]),
      (byte) (addr[11] & PRIVATE_IPV6_RANGES[base + 27]),
      (byte) (addr[12] & PRIVATE_IPV6_RANGES[base + 28]),
      (byte) (addr[13] & PRIVATE_IPV6_RANGES[base + 29]),
      (byte) (addr[14] & PRIVATE_IPV6_RANGES[base + 30]),
      (byte) (addr[15] & PRIVATE_IPV6_RANGES[base + 31]),
    };

    // Benchmarking indicates that this is better than
    //  return ByteBuffer.wrap(maskedAddr).equals(
    //      ByteBuffer.wrap(PRIVATE_IPV6_RANGES, base, 16));
    // (after optimization)
    return maskedAddr[0] == PRIVATE_IPV6_RANGES[base]
        && maskedAddr[1] == PRIVATE_IPV6_RANGES[base + 1]
        && maskedAddr[2] == PRIVATE_IPV6_RANGES[base + 2]
        && maskedAddr[3] == PRIVATE_IPV6_RANGES[base + 3]
        && maskedAddr[4] == PRIVATE_IPV6_RANGES[base + 4]
        && maskedAddr[5] == PRIVATE_IPV6_RANGES[base + 5]
        && maskedAddr[6] == PRIVATE_IPV6_RANGES[base + 6]
        && maskedAddr[7] == PRIVATE_IPV6_RANGES[base + 7]
        && maskedAddr[8] == PRIVATE_IPV6_RANGES[base + 8]
        && maskedAddr[9] == PRIVATE_IPV6_RANGES[base + 9]
        && maskedAddr[10] == PRIVATE_IPV6_RANGES[base + 10]
        && maskedAddr[11] == PRIVATE_IPV6_RANGES[base + 11]
        && maskedAddr[12] == PRIVATE_IPV6_RANGES[base + 12]
        && maskedAddr[13] == PRIVATE_IPV6_RANGES[base + 13]
        && maskedAddr[14] == PRIVATE_IPV6_RANGES[base + 14]
        && maskedAddr[15] == PRIVATE_IPV6_RANGES[base + 15];
  }

  private static InetAddress parseIpAddressAndMaybePort(String str) {
    if (str == null || str.length() == 0) {
      return null;
    }
    if (str.charAt(0) == '[') {
      int posClose = str.indexOf(']', 1);
      if (posClose == -1) {
        return null;
      }
      return parseIpAddress(str.substring(1, posClose));
    }
    int posColon = str.indexOf(':');
    if (posColon == -1) {
      return parseIpAddress(str);
    } else {
      return parseIpAddress(str.substring(0, posColon));
    }
  }

  public static InetAddress parseIpAddress(String str) {
    if (str.length() == 0) {
      return null;
    }
    char firstChar = str.charAt(0);
    if (!(firstChar >= '0' && firstChar <= '9' || firstChar == ':')) {
      return null; // probably a name instead
    }
    try {
      return InetAddress.getByName(str);
    } catch (UnknownHostException e) {
      return null; // should not happen
    }
  }
}
