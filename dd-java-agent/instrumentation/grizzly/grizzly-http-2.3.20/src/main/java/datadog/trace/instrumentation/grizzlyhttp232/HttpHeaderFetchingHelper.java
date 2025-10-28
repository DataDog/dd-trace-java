package datadog.trace.instrumentation.grizzlyhttp232;

import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.io.InputBuffer;

/**
 * Helper till we have support for invokedynamic-based caching on bytebuddy:
 * https://github.com/raphw/byte-buddy/issues/1009
 */
public final class HttpHeaderFetchingHelper {
  private HttpHeaderFetchingHelper() {}

  private static final Field HTTP_HEADER_FIELD = prepareField();

  private static Field prepareField() {
    Field httpHeaderField = null;
    try {
      httpHeaderField = InputBuffer.class.getDeclaredField("httpHeader");
    } catch (NoSuchFieldException e) {
      throw new UndeclaredThrowableException(e);
    }
    httpHeaderField.setAccessible(true);
    return httpHeaderField;
  }

  public static HttpHeader fetchHttpHeader(InputBuffer inputBuffer) {
    try {
      return (HttpHeader) HTTP_HEADER_FIELD.get(inputBuffer);
    } catch (IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}
