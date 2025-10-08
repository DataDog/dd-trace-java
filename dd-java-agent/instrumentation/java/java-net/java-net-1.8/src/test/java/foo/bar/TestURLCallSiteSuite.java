package foo.bar;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestURLCallSiteSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestURLCallSiteSuite.class);

  public static URL url(final String value) {
    try {
      LOGGER.debug("Before ctor {}", value);
      final URL url = new URL(value);
      LOGGER.debug("After ctor {}", url);
      return url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static URL url(
      final String protocol, final String host, final int port, final String file) {
    try {
      LOGGER.debug("Before ctor {} {} {} {}", protocol, host, port, file);
      final URL url = new URL(protocol, host, port, file);
      LOGGER.debug("After ctor {}", url);
      return url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static URL url(
      final String protocol,
      final String host,
      final int port,
      final String file,
      final URLStreamHandler handler) {
    try {
      LOGGER.debug("Before ctor {} {} {} {} {}", protocol, host, port, file, handler);
      final URL url = new URL(protocol, host, port, file, handler);
      LOGGER.debug("After ctor {}", url);
      return url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static URL url(final String protocol, final String host, final String file) {
    try {
      LOGGER.debug("Before ctor {} {} {}", protocol, host, file);
      final URL url = new URL(protocol, host, file);
      LOGGER.debug("After ctor {}", url);
      return url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static URL url(final URL context, final String spec) {
    try {
      LOGGER.debug("Before ctor {} {}", context, spec);
      final URL url = new URL(context, spec);
      LOGGER.debug("After ctor {}", url);
      return url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static URL url(final URL context, final String spec, final URLStreamHandler handler) {
    try {
      LOGGER.debug("Before ctor {} {} {}", context, spec, handler);
      final URL url = new URL(context, spec, handler);
      LOGGER.debug("After ctor {}", url);
      return url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public static URI toURI(final URL url) {
    try {
      LOGGER.debug("Before toURI {}", url);
      final URI result = url.toURI();
      LOGGER.debug("After toURI {}", result);
      return result;
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public static String toString(final URL url) {
    LOGGER.debug("Before toString {}", url);
    final String result = url.toString();
    LOGGER.debug("After toString {}", result);
    return result;
  }

  public static String toExternalForm(final URL url) {
    LOGGER.debug("Before toExternalForm {}", url);
    final String result = url.toExternalForm();
    LOGGER.debug("After toExternalForm {}", result);
    return result;
  }

  public static URLConnection openConnection(final URL url) {
    try {
      LOGGER.debug("Before openConnection {}", url);
      final URLConnection result = url.openConnection();
      LOGGER.debug("After openConnection {}", result);
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static URLConnection openConnection(final URL url, final Proxy proxy) {
    try {
      LOGGER.debug("Before openConnection {}", url);
      final URLConnection result = url.openConnection(proxy);
      LOGGER.debug("After openConnection {}", result);
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static InputStream openStream(final URL url) {
    try {
      LOGGER.debug("Before openStream {}", url);
      final InputStream result = url.openStream();
      LOGGER.debug("After openStream {}", result);
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static Object getContent(final URL url) {
    try {
      LOGGER.debug("Before getContent {}", url);
      final Object result = url.getContent();
      LOGGER.debug("After getContent {}", result);
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static Object getContent(final URL url, final Class<?>... classes) {
    try {
      LOGGER.debug("Before getContent {} {}", url, classes);
      final Object result = url.getContent(classes);
      LOGGER.debug("After getContent {}", result);
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
