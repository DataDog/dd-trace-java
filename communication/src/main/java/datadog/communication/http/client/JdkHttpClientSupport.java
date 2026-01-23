package datadog.communication.http.client;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.api.InstrumenterConfig;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized support class for JDK HttpClient reflection-based loading.
 * Performs version and configuration checks once, then caches all reflection results.
 */
final class JdkHttpClientSupport {

  private static final Logger log = LoggerFactory.getLogger(JdkHttpClientSupport.class);

  // Cached JDK HttpClient classes and methods (null if not available)
  static final Class<?> JDK_CLIENT_CLASS;
  static final Class<?> JDK_CLIENT_BUILDER_CLASS;
  static final Constructor<?> JDK_CLIENT_BUILDER_CONSTRUCTOR;

  static final Class<?> JDK_URL_CLASS;
  static final Method JDK_URL_WRAP_METHOD;
  static final Class<?> JDK_URL_BUILDER_CLASS;
  static final Constructor<?> JDK_URL_BUILDER_CONSTRUCTOR;

  static final Class<?> JDK_REQUEST_BUILDER_CLASS;
  static final Constructor<?> JDK_REQUEST_BUILDER_CONSTRUCTOR;

  static final Class<?> JDK_BODY_CLASS;
  static final Method JDK_BODY_OF_STRING_METHOD;
  static final Method JDK_BODY_OF_MSGPACK_METHOD;
  static final Method JDK_BODY_OF_GZIP_METHOD;
  static final Method JDK_BODY_MULTIPART_BUILDER_METHOD;

  static {
    ReflectionCache cache = loadJdkClasses();
    JDK_CLIENT_CLASS = cache.clientClass;
    JDK_CLIENT_BUILDER_CLASS = cache.clientBuilderClass;
    JDK_CLIENT_BUILDER_CONSTRUCTOR = cache.clientBuilderConstructor;

    JDK_URL_CLASS = cache.urlClass;
    JDK_URL_WRAP_METHOD = cache.urlWrapMethod;
    JDK_URL_BUILDER_CLASS = cache.urlBuilderClass;
    JDK_URL_BUILDER_CONSTRUCTOR = cache.urlBuilderConstructor;

    JDK_REQUEST_BUILDER_CLASS = cache.requestBuilderClass;
    JDK_REQUEST_BUILDER_CONSTRUCTOR = cache.requestBuilderConstructor;

    JDK_BODY_CLASS = cache.bodyClass;
    JDK_BODY_OF_STRING_METHOD = cache.bodyOfStringMethod;
    JDK_BODY_OF_MSGPACK_METHOD = cache.bodyOfMsgpackMethod;
    JDK_BODY_OF_GZIP_METHOD = cache.bodyOfGzipMethod;
    JDK_BODY_MULTIPART_BUILDER_METHOD = cache.bodyMultipartBuilderMethod;
  }

  @SuppressForbidden // Check Java version and load JDK11+ classes
  private static ReflectionCache loadJdkClasses() {
    // Check prerequisites before attempting to load classes
    boolean shouldLoad = shouldLoadJdkHttpClient();

    Class<?> clientClass = null;
    Class<?> clientBuilderClass = null;
    Constructor<?> clientBuilderConstructor = null;

    Class<?> urlClass = null;
    Method urlWrapMethod = null;
    Class<?> urlBuilderClass = null;
    Constructor<?> urlBuilderConstructor = null;

    Class<?> requestBuilderClass = null;
    Constructor<?> requestBuilderConstructor = null;

    Class<?> bodyClass = null;
    Method bodyOfStringMethod = null;
    Method bodyOfMsgpackMethod = null;
    Method bodyOfGzipMethod = null;
    Method bodyMultipartBuilderMethod = null;

    if (shouldLoad) {
      try {
        // Load HttpClient classes
        clientClass = Class.forName("datadog.communication.http.jdk.JdkHttpClient");
        clientBuilderClass = Class.forName("datadog.communication.http.jdk.JdkHttpClient$JdkHttpClientBuilder");
        clientBuilderConstructor = clientBuilderClass.getDeclaredConstructor();
        clientBuilderConstructor.setAccessible(true);

        // Load HttpUrl classes
        urlClass = Class.forName("datadog.communication.http.jdk.JdkHttpUrl");
        urlWrapMethod = urlClass.getMethod("wrap", URI.class);
        urlBuilderClass = Class.forName("datadog.communication.http.jdk.JdkHttpUrl$JdkHttpUrlBuilder");
        urlBuilderConstructor = urlBuilderClass.getDeclaredConstructor();
        urlBuilderConstructor.setAccessible(true);

        // Load HttpRequest classes
        requestBuilderClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequest$JdkHttpRequestBuilder");
        requestBuilderConstructor = requestBuilderClass.getDeclaredConstructor();
        requestBuilderConstructor.setAccessible(true);

        // Load HttpRequestBody class and methods
        bodyClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequestBody");
        bodyOfStringMethod = bodyClass.getMethod("ofString", String.class);
        bodyOfMsgpackMethod = bodyClass.getMethod("ofMsgpack", List.class);
        bodyOfGzipMethod = bodyClass.getMethod("ofGzip", HttpRequestBody.class);
        bodyMultipartBuilderMethod = bodyClass.getMethod("multipartBuilder");

        log.debug("JDK HttpClient support loaded successfully");
      } catch (ClassNotFoundException e) {
        log.debug("JDK HttpClient classes not available (expected on Java < 11): {}", e.getMessage());
      } catch (NoSuchMethodException e) {
        log.warn("JDK HttpClient classes found but methods missing: {}", e.getMessage());
      } catch (Exception e) {
        log.warn("Unexpected error loading JDK HttpClient classes: {}", e.getMessage());
      }
    } else {
      log.debug("JDK HttpClient support disabled (Java version or configuration)");
    }

    return new ReflectionCache(
        clientClass, clientBuilderClass, clientBuilderConstructor,
        urlClass, urlWrapMethod, urlBuilderClass, urlBuilderConstructor,
        requestBuilderClass, requestBuilderConstructor,
        bodyClass, bodyOfStringMethod, bodyOfMsgpackMethod, bodyOfGzipMethod, bodyMultipartBuilderMethod);
  }

  /**
   * Determines if JDK HttpClient should be loaded based on Java version and configuration.
   */
  private static boolean shouldLoadJdkHttpClient() {
    // Check Java version first - JDK HttpClient requires Java 11+
    if (!JavaVirtualMachine.isJavaVersionAtLeast(11)) {
      return false;
    }

    // Check configuration - if user forces okhttp, don't load JDK classes
    String implementation = InstrumenterConfig.get().getHttpClientImplementation();
    if (implementation != null && "okhttp".equalsIgnoreCase(implementation.trim())) {
      return false;
    }

    return true;
  }

  /**
   * Returns true if JDK HttpClient support is available.
   */
  static boolean isAvailable() {
    return JDK_CLIENT_BUILDER_CONSTRUCTOR != null;
  }

  private JdkHttpClientSupport() {
    // Utility class
  }

  /**
   * Holder for all reflection-loaded classes and methods.
   */
  private static final class ReflectionCache {
    final Class<?> clientClass;
    final Class<?> clientBuilderClass;
    final Constructor<?> clientBuilderConstructor;

    final Class<?> urlClass;
    final Method urlWrapMethod;
    final Class<?> urlBuilderClass;
    final Constructor<?> urlBuilderConstructor;

    final Class<?> requestBuilderClass;
    final Constructor<?> requestBuilderConstructor;

    final Class<?> bodyClass;
    final Method bodyOfStringMethod;
    final Method bodyOfMsgpackMethod;
    final Method bodyOfGzipMethod;
    final Method bodyMultipartBuilderMethod;

    ReflectionCache(
        Class<?> clientClass, Class<?> clientBuilderClass, Constructor<?> clientBuilderConstructor,
        Class<?> urlClass, Method urlWrapMethod, Class<?> urlBuilderClass, Constructor<?> urlBuilderConstructor,
        Class<?> requestBuilderClass, Constructor<?> requestBuilderConstructor,
        Class<?> bodyClass, Method bodyOfStringMethod, Method bodyOfMsgpackMethod,
        Method bodyOfGzipMethod, Method bodyMultipartBuilderMethod) {
      this.clientClass = clientClass;
      this.clientBuilderClass = clientBuilderClass;
      this.clientBuilderConstructor = clientBuilderConstructor;
      this.urlClass = urlClass;
      this.urlWrapMethod = urlWrapMethod;
      this.urlBuilderClass = urlBuilderClass;
      this.urlBuilderConstructor = urlBuilderConstructor;
      this.requestBuilderClass = requestBuilderClass;
      this.requestBuilderConstructor = requestBuilderConstructor;
      this.bodyClass = bodyClass;
      this.bodyOfStringMethod = bodyOfStringMethod;
      this.bodyOfMsgpackMethod = bodyOfMsgpackMethod;
      this.bodyOfGzipMethod = bodyOfGzipMethod;
      this.bodyMultipartBuilderMethod = bodyMultipartBuilderMethod;
    }
  }
}
