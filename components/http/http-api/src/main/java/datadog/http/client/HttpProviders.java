package datadog.http.client;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class HttpProviders {
  private static volatile boolean compatibilityMode = false;

  private static volatile Constructor<?> HTTP_CLIENT_BUILDER_CONSTRUCTOR;
  private static volatile Constructor<?> HTTP_REQUEST_BUILDER_CONSTRUCTOR;
  private static volatile Constructor<?> HTTP_URL_BUILDER_CONSTRUCTOR;
  private static volatile Method HTTP_URL_PARSE_METHOD;
  private static volatile Method HTTP_REQUEST_BODY_OF_STRING_METHOD;

  private HttpProviders() {
  }

  public static void forceCompatClient() {
    compatibilityMode = true;
    HTTP_CLIENT_BUILDER_CONSTRUCTOR = null;
    HTTP_REQUEST_BUILDER_CONSTRUCTOR = null;
    HTTP_URL_BUILDER_CONSTRUCTOR = null;
    HTTP_URL_PARSE_METHOD = null;
    HTTP_REQUEST_BODY_OF_STRING_METHOD = null;
  }

  static HttpClient.Builder newClientBuilder() {
    if (HTTP_CLIENT_BUILDER_CONSTRUCTOR == null) {
      HTTP_CLIENT_BUILDER_CONSTRUCTOR = findConstructor(
          "datadog.http.client.jdk.JdkHttpClient$Builder",
          "datadog.http.client.okhttp.OkHttpClient$Builder");
    }
    try {
      return (HttpClient.Builder) HTTP_CLIENT_BUILDER_CONSTRUCTOR.newInstance();
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("Failed to call constructor", e);
    }
  }

  static HttpRequest.Builder newRequestBuilder() {
    if (HTTP_REQUEST_BUILDER_CONSTRUCTOR == null) {
      HTTP_REQUEST_BUILDER_CONSTRUCTOR = findConstructor(
          "datadog.http.client.jdk.JdkHttpRequest$Builder",
          "datadog.http.client.okhttp.OkHttpRequest$Builder");
    }
    try {
      return (HttpRequest.Builder) HTTP_REQUEST_BUILDER_CONSTRUCTOR.newInstance();
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("Failed to call constructor", e);
    }
  }

  static HttpUrl.Builder newUrlBuilder() {
    if (HTTP_URL_BUILDER_CONSTRUCTOR == null) {
      HTTP_URL_BUILDER_CONSTRUCTOR = findConstructor(
          "datadog.http.client.jdk.JdkHttpUrl$Builder",
          "datadog.http.client.okhttp.OkHttpUrl$Builder");
    }
    try {
      return (HttpUrl.Builder) HTTP_URL_BUILDER_CONSTRUCTOR.newInstance();
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("Failed to call constructor", e);
    }
  }

  static HttpUrl httpUrlParse(String url) {
    if (HTTP_URL_PARSE_METHOD == null) {
      HTTP_URL_PARSE_METHOD = findMethod(
          "datadog.http.client.jdk.JdkHttpUrl",
          "datadog.http.client.okhttp.OkHttpUrl",
          "parse",
          String.class);
    }
    try {
      return (HttpUrl) HTTP_URL_PARSE_METHOD.invoke(null, url);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("Failed to call parse method", e);
    }
  }

  static HttpRequestBody requestBodyOfString(String content) {
    if (HTTP_REQUEST_BODY_OF_STRING_METHOD == null) {
      HTTP_REQUEST_BODY_OF_STRING_METHOD = findMethod(
          "datadog.http.client.jdk.JdkHttpRequestBody",
          "datadog.http.client.okhttp.OkHttpRequestBody",
          "ofString",
          String.class);
    }
    try {
      return (HttpRequestBody) HTTP_REQUEST_BODY_OF_STRING_METHOD.invoke(null, content);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException("Failed to call ofString method", e);
    }
  }

  private static Method findMethod(String defaultClientClass, String compatClientClass, String name, Class<?>... parameterTypes) {
    Class<?> clientClass = findClientClass(defaultClientClass, compatClientClass);
    try {
      return clientClass.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Failed to find " + name + " method", e);
    }
  }

  private static Constructor<?> findConstructor(String defaultClientClass, String compatClientClass) {
    Class<?> clientClass = findClientClass(defaultClientClass, compatClientClass);
    try {
      return clientClass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw new RuntimeException("Failed to find constructor", e);
    }
  }


  @NonNull
  private static Class<?> findClientClass(String defaultClientClass, String compatClientClass) {
    Class<?> clazz = null;
    // Load the default client class
    if (!compatibilityMode) {
      try {
        clazz = Class.forName(defaultClientClass);
      } catch (ClassNotFoundException ignored) {
        compatibilityMode = true;
      }
    }
    // If not loaded, load the compat client class
    if (clazz == null) {
      try {
        clazz = Class.forName(compatClientClass);
      } catch (ClassNotFoundException ignored) {
      }
    }
    // If no class loaded, raise the illegal state
    if (clazz == null) {
      throw new IllegalStateException("No HttpClientBuilder implementation found");
    }
    return clazz;
  }
}
