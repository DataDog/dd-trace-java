package datadog.smoketest.springboot.filter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import lombok.experimental.Delegate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * This filter will trigger a visit of all session stored objects when a request includes the header
 * {@code X-Session-Visitor}
 */
public class SessionVisitorFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
      throws ServletException, IOException {
    request = hasSessionHeader(request) ? new RequestWrapper(request) : request;
    filterChain.doFilter(request, response);
  }

  private static boolean hasSessionHeader(final HttpServletRequest request) {
    return request.getHeader("X-Session-Visitor") != null;
  }

  private static class RequestWrapper extends HttpServletRequestWrapper {
    public RequestWrapper(HttpServletRequest request) {
      super(request);
    }

    @Override
    public HttpSession getSession(boolean create) {
      return wrapSession(super.getSession(create));
    }

    @Override
    public HttpSession getSession() {
      return wrapSession(super.getSession());
    }

    private HttpSession wrapSession(final HttpSession session) {
      if (session == null || session instanceof SessionWrapper) {
        return session;
      }
      return new SessionWrapper(session);
    }
  }

  private static class SessionWrapper implements HttpSession {

    @Delegate private final HttpSession delegate;

    private SessionWrapper(final HttpSession delegate) {
      this.delegate = delegate;
    }

    public void setAttribute(final String name, final Object value) {
      new DumbVisitor().visit(value);
      delegate.setAttribute(name, value);
    }
  }

  /**
   * Extremely unsafe visitor class used to trigger some bad behaviour with Hibernate and lazy
   * properties
   */
  private static class DumbVisitor {
    private final Set<Object> visited = new HashSet<>();

    public void visit(final Object value) {
      if (value == null || visited.contains(value)) {
        return;
      }
      visited.add(value);
      if (value.getClass().isArray()) {
        for (Object item : (Object[]) value) {
          visitObject(item);
        }
      } else if (value instanceof Iterable) {
        for (Object item : (Iterable<?>) value) {
          visitObject(item);
        }
      } else if (value instanceof Map) {
        for (Object item : ((Map<?, ?>) value).values()) {
          visitObject(item);
        }
      } else {
        visitObject(value);
      }
    }

    private void visitObject(final Object object) {
      // ignore java types
      if (object.getClass().getName().startsWith("java.")) {
        return;
      }
      Class<?> klass = object.getClass();
      while (klass != Object.class) {
        for (final Field field : klass.getDeclaredFields()) {
          try {
            field.setAccessible(true);
            final Object value = field.get(object);
            visit(value);
          } catch (final Throwable e) {
            // ignore it
          }
        }
        klass = klass.getSuperclass();
      }
    }
  }
}
