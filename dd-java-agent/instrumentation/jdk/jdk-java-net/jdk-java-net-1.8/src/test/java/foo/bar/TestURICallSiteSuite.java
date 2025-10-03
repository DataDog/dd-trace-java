package foo.bar;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestURICallSiteSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestURICallSiteSuite.class);

  public static URI uri(final String value) {
    try {
      LOGGER.debug("Before ctor {}", value);
      final URI uri = new URI(value);
      LOGGER.debug("After ctor {}", uri);
      return uri;
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static URI uri(
      final String scheme,
      final String userInfo,
      final String host,
      final int port,
      final String path,
      final String query,
      final String fragment) {
    try {
      LOGGER.debug(
          "Before ctor {} {} {} {} {} {} {}", scheme, userInfo, host, port, path, query, fragment);
      final URI uri = new URI(scheme, userInfo, host, port, path, query, fragment);
      LOGGER.debug("After ctor {}", uri);
      return uri;
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static URI uri(
      final String scheme,
      final String authority,
      final String path,
      final String query,
      final String fragment) {
    try {
      LOGGER.debug("Before ctor {} {} {} {} {}", scheme, authority, path, query, fragment);
      final URI uri = new URI(scheme, authority, path, query, fragment);
      LOGGER.debug("After ctor {}", uri);
      return uri;
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static URI uri(
      final String scheme, final String path, final String query, final String fragment) {
    try {
      LOGGER.debug("Before ctor {} {} {} {}", scheme, path, query, fragment);
      final URI uri = new URI(scheme, path, query, fragment);
      LOGGER.debug("After ctor {}", uri);
      return uri;
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static URI uri(final String scheme, final String ssp, final String fragment) {
    try {
      LOGGER.debug("Before ctor {} {} {}", scheme, ssp, fragment);
      final URI uri = new URI(scheme, ssp, fragment);
      LOGGER.debug("After ctor {}", uri);
      return uri;
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static URI create(final String str) {
    LOGGER.debug("Before create {}", str);
    final URI uri = URI.create(str);
    LOGGER.debug("After create {}", uri);
    return uri;
  }

  public static URI normalize(final URI uri) {
    LOGGER.debug("Before normalize {}", uri);
    final URI result = uri.normalize();
    LOGGER.debug("After normalize {}", result);
    return result;
  }

  public static String toString(final URI uri) {
    LOGGER.debug("Before toString {}", uri);
    final String result = uri.toString();
    LOGGER.debug("After toString {}", result);
    return result;
  }

  public static String toASCIIString(final URI uri) {
    LOGGER.debug("Before toAsciiString {}", uri);
    final String result = uri.toASCIIString();
    LOGGER.debug("After toAsciiString {}", result);
    return result;
  }

  public static URL toURL(final URI uri) throws MalformedURLException {
    LOGGER.debug("Before toURL {}", uri);
    final URL result = uri.toURL();
    LOGGER.debug("After toURL {}", result);
    return result;
  }
}
