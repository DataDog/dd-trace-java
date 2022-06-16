package datadog.trace.bootstrap.instrumentation.decorator.http;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.*;

public abstract class HttpHeadersClassifier implements AgentPropagation.KeyClassifier {

  private final Map<String, List<String>> headersCache = new LinkedHashMap<>();

  public abstract boolean nextHeader(String name, String value);

  public abstract void doneHeaders(Map<String, List<String>> headers);

  @Override
  public boolean accept(String key, String value) {
    String name = key.toLowerCase();
    if (!saveHeader(name, value)) {
      return false;
    }

    return nextHeader(name, value);
  }

  public void done() {
    doneHeaders(headersCache);
  }

  private boolean saveHeader(String name, String value) {
    List<String> strings = headersCache.get(name);
    if (strings == null) {
      strings = new ArrayList<>(1);
      headersCache.put(name, strings);
    }
    return strings.add(value);
  }
}
