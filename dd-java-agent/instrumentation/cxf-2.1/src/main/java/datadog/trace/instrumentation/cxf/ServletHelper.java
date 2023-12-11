package datadog.trace.instrumentation.cxf;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Probably one day we should put it in the bootstrap if used in a lot of places
public class ServletHelper {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServletHelper.class);
  private static final Class<?> JAVAX_SERVLET_REQUEST_CLASS =
      safelyLocateClass("javax.servlet.ServletRequest");
  private static final Class<?> JAKARTA_SERVLET_REQUEST_CLASS =
      safelyLocateClass("jakarta.servlet.ServletRequest");
  private static final MethodHandle JAVAX_ATTRIBUTE_ACCESSOR =
      safelyUnreflectServletRequestAttributeGetter(JAVAX_SERVLET_REQUEST_CLASS);
  private static final MethodHandle JAKARTA_ATTRIBUTE_ACCESSOR =
      safelyUnreflectServletRequestAttributeGetter(JAKARTA_SERVLET_REQUEST_CLASS);

  private static Class<?> safelyLocateClass(final String name) {
    try {
      return Class.forName(name, false, ServletHelper.class.getClassLoader());
    } catch (Throwable t) {
      // can be expected
    }
    return null;
  }

  private static MethodHandle safelyUnreflectServletRequestAttributeGetter(
      final Class<?> servletRequestClass) {
    if (servletRequestClass != null) {
      try {
        return MethodHandles.lookup()
            .unreflect(servletRequestClass.getMethod("getAttribute", String.class));
      } catch (Throwable t) {
        if (JAVAX_ATTRIBUTE_ACCESSOR == null) {
          LOGGER.debug(
              "Unable to lookup getAttribute for servlet request class. The cxf-core instrumentation might not work as expected",
              t);
        }
      }
    }
    return null;
  }

  public static Object getServletRequestAttribute(final Object servletRequest, final String name) {
    final MethodHandle mh =
        JAVAX_SERVLET_REQUEST_CLASS != null
                && JAVAX_SERVLET_REQUEST_CLASS.isInstance(servletRequest)
            ? JAVAX_ATTRIBUTE_ACCESSOR
            : JAKARTA_SERVLET_REQUEST_CLASS != null
                    && JAKARTA_SERVLET_REQUEST_CLASS.isInstance(servletRequest)
                ? JAKARTA_ATTRIBUTE_ACCESSOR
                : null;
    if (mh != null) {
      try {
        return mh.invoke(servletRequest, name);
      } catch (Throwable e) {
        // nothing we can do here. Silently ignore
      }
    }
    return null;
  }
}
