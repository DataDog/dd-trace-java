package datadog.trace.api.appsec.api.security.model;

import java.util.List;
import java.util.Map;

public class Endpoint {

  public enum Type {
    REST
  }

  public enum Method {
    HEAD("HEAD"),
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    PATCH("PATCH"),
    OPTIONS("OPTIONS"),
    DELETE("DELETE"),
    TRACE("TRACE"),
    ALL("*");

    private final String name;

    Method(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public static Method parseMethod(final String name) {
      for (final Method method : Method.values()) {
        if (method.getName().equals(name)) {
          return method;
        }
      }
      throw new IllegalArgumentException("Unknown method: " + name);
    }
  }

  public enum Operation {
    HTTP_REQUEST("http.request");

    private final String name;

    Operation(final String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public static Operation parseOperation(final String name) {
      for (final Operation op : Operation.values()) {
        if (op.getName().equals(name)) {
          return op;
        }
      }
      throw new IllegalArgumentException("Unknown operation: " + name);
    }
  }

  private Type type;
  private Method method;
  private String path;
  private Operation operation;
  private List<String> requestBodyType;
  private List<String> responseBodyType;
  private List<Integer> responseCode;
  private List<String> authentication;
  private Map<String, Object> metadata;

  public Type getType() {
    return type;
  }

  public void setType(final Type type) {
    this.type = type;
  }

  public Method getMethod() {
    return method;
  }

  public void setMethod(final Method method) {
    this.method = method;
  }

  public String getPath() {
    return path;
  }

  public void setPath(final String path) {
    this.path = path;
  }

  public Operation getOperation() {
    return operation;
  }

  public void setOperation(final Operation operation) {
    this.operation = operation;
  }

  public List<String> getRequestBodyType() {
    return requestBodyType;
  }

  public void setRequestBodyType(final List<String> requestBodyType) {
    this.requestBodyType = requestBodyType;
  }

  public List<String> getResponseBodyType() {
    return responseBodyType;
  }

  public void setResponseBodyType(final List<String> responseBodyType) {
    this.responseBodyType = responseBodyType;
  }

  public List<Integer> getResponseCode() {
    return responseCode;
  }

  public void setResponseCode(final List<Integer> responseCode) {
    this.responseCode = responseCode;
  }

  public List<String> getAuthentication() {
    return authentication;
  }

  public void setAuthentication(final List<String> authentication) {
    this.authentication = authentication;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(final Map<String, Object> metadata) {
    this.metadata = metadata;
  }
}
