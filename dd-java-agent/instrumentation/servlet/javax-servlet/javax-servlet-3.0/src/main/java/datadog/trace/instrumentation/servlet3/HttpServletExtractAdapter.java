package datadog.trace.instrumentation.servlet3;

import static java.util.Collections.emptyEnumeration;
import static java.util.Collections.enumeration;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletResponse;
import javax.servlet.ServletResponseWrapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class HttpServletExtractAdapter<T> implements AgentPropagation.ContextVisitor<T> {
  abstract Enumeration<String> getHeaderNames(T t);

  abstract String getHeader(T t, String name);

  @Override
  public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
    Enumeration<String> headerNames = getHeaderNames(carrier);
    while (headerNames.hasMoreElements()) {
      String header = headerNames.nextElement();
      if (!classifier.accept(header, getHeader(carrier, header))) {
        break;
      }
    }
  }

  public static final class Request extends HttpServletExtractAdapter<HttpServletRequest> {
    public static final Request GETTER = new Request();

    @Override
    Enumeration<String> getHeaderNames(HttpServletRequest request) {
      final Enumeration<String> ret = request.getHeaderNames();
      return ret != null ? ret : emptyEnumeration();
    }

    @Override
    String getHeader(HttpServletRequest request, String name) {
      return request.getHeader(name);
    }
  }

  /**
   * Reads response headers through the Servlet 3.0 accessors. A response class compiled against
   * Servlet 2.5 (for example an old wrapper) does not implement them, and calling them throws
   * {@link AbstractMethodError}. Such classes are remembered so they are not tried again, and
   * headers are read from a wrapped delegate that does implement the accessors, if there is one.
   */
  public static final class Response extends HttpServletExtractAdapter<HttpServletResponse> {
    public static final Response GETTER = new Response();

    /** Bounds how many wrappers are unwrapped while looking for a usable response. */
    private static final int MAX_UNWRAP_DEPTH = 32;

    /**
     * Set once any response class turned out not to implement the header accessors. Until then
     * responses are read directly, so healthy applications only pay for this flag.
     */
    private static boolean anyUnsupported;

    private static final HeaderAccessors HEADER_ACCESSORS = new HeaderAccessors();

    @Override
    public void forEachKey(HttpServletResponse carrier, AgentPropagation.KeyClassifier classifier) {
      HttpServletResponse source = anyUnsupported ? headerSource(carrier) : carrier;
      while (source != null) {
        // only the accessor calls are guarded: an AbstractMethodError from the classifier callback
        // is not evidence that this response class lacks the accessors
        Enumeration<String> headerNames;
        try {
          headerNames = getHeaderNames(source);
        } catch (AbstractMethodError e) {
          source = markUnsupported(source);
          continue;
        }
        while (headerNames.hasMoreElements()) {
          String header = headerNames.nextElement();
          String value;
          try {
            value = getHeader(source, header);
          } catch (AbstractMethodError e) {
            // only partly implemented: stop instead of retrying, which would re-emit keys
            markUnsupported(source);
            return;
          }
          if (!classifier.accept(header, value)) {
            return;
          }
        }
        return;
      }
    }

    /** Remembers that the response's class lacks header access; returns the next source to try. */
    private static HttpServletResponse markUnsupported(HttpServletResponse response) {
      anyUnsupported = true;
      HEADER_ACCESSORS.get(response.getClass()).set(false);
      return headerSource(response);
    }

    /** The response, or the nearest wrapped delegate, whose class supports header access. */
    private static HttpServletResponse headerSource(HttpServletResponse response) {
      ServletResponse current = response;
      for (int depth = 0;
          depth < MAX_UNWRAP_DEPTH && current instanceof HttpServletResponse;
          depth++) {
        if (HEADER_ACCESSORS.get(current.getClass()).get()) {
          return (HttpServletResponse) current;
        }
        if (!(current instanceof ServletResponseWrapper)) {
          return null;
        }
        current = ((ServletResponseWrapper) current).getResponse();
      }
      return null;
    }

    /** Does this response class implement the Servlet 3.0 header accessors? */
    static boolean supportsHeaderAccess(Class<?> type) {
      return isConcrete(type, "getHeaderNames") && isConcrete(type, "getHeader", String.class);
    }

    /** An interface method that is still abstract on the class means AbstractMethodError. */
    private static boolean isConcrete(Class<?> type, String name, Class<?>... parameterTypes) {
      try {
        return !Modifier.isAbstract(type.getMethod(name, parameterTypes).getModifiers());
      } catch (NoSuchMethodException e) {
        return false;
      } catch (SecurityException e) {
        return true; // cannot tell; the catch in forEachKey still protects us
      }
    }

    /** Per response class: does it implement the Servlet 3.0 header accessors? */
    private static final class HeaderAccessors extends ClassValue<AtomicBoolean> {
      @Override
      protected AtomicBoolean computeValue(Class<?> type) {
        return new AtomicBoolean(supportsHeaderAccess(type));
      }
    }

    @Override
    Enumeration<String> getHeaderNames(HttpServletResponse response) {
      final Collection<String> headerNames = response.getHeaderNames();
      return headerNames != null ? enumeration(headerNames) : emptyEnumeration();
    }

    @Override
    String getHeader(HttpServletResponse response, String name) {
      return response.getHeader(name);
    }
  }
}
