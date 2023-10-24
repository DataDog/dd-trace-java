package datadog.trace.instrumentation.pulsar;

public class UrlParser {
  private UrlParser() {}

  public static UrlData parseUrl(String url) {
    // if there are multiple addresses then they are separated with , or ;
    if (url == null || url.indexOf(',') != -1 || url.indexOf(';') != -1) {
      return null;
    }

    int protocolEnd = url.indexOf("://");
    if (protocolEnd == -1) {
      return null;
    }
    int authorityStart = protocolEnd + 3;
    int authorityEnd = url.indexOf('/', authorityStart);
    if (authorityEnd == -1) {
      authorityEnd = url.length();
    }
    String authority = url.substring(authorityStart, authorityEnd);
    int portStart = authority.indexOf(':');

    String host;
    Integer port;
    if (portStart == -1) {
      host = authority;
      port = null;
    } else {
      host = authority.substring(0, portStart);
      try {
        port = Integer.parseInt(authority.substring(portStart + 1));
      } catch (NumberFormatException exception) {
        port = null;
      }
    }

    return new UrlData(host, port);
  }
}
