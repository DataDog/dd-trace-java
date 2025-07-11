package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import java.util.Objects;
import java.util.stream.Stream;

public class InferredProxyContext implements ImplicitContextKeyed {
  public static final ContextKey<InferredProxyContext> CONTEXT_KEY =
      ContextKey.named("inferred-proxy-key");
  private String proxyName;
  private String startTime;
  private String domainName;
  private String httpMethod;
  private String path;
  private String stage;

  public static InferredProxyContext fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  public InferredProxyContext() {}

  public void setProxyName(String name) {
    this.proxyName = name;
  }

  public String getProxyName() {
    return this.proxyName;
  }

  public void setStartTime(String time) {
    this.startTime = time;
  }

  public String getStartTime() {
    return this.startTime;
  }

  public void setDomainName(String name) {
    this.domainName = name;
  }

  public String getDomainName() {
    return this.domainName;
  }

  public void setHttpMethod(String httpMethod) {
    this.httpMethod = httpMethod;
  }

  public String getHttpMethod() {
    return this.httpMethod;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getPath() {
    return this.path;
  }

  public void setStage(String stage) {
    this.stage = stage;
  }

  public String getStage() {
    return this.stage;
  }

  public boolean validContext() {
    return Stream.of(proxyName, startTime, domainName, httpMethod, path, stage)
        .allMatch(Objects::nonNull);
  }

  /**
   * Creates a new context with this value under its chosen key.
   *
   * @param context the context to copy the original values from.
   * @return the new context with the implicitly keyed value.
   * @see Context#with(ImplicitContextKeyed)
   */
  @Override
  public Context storeInto(Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
