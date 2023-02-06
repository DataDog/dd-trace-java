package datadog.trace.instrumentation.undertow;

import io.undertow.server.handlers.form.FormData;
import java.util.*;

public class FormDataMap implements Map<String, Collection<String>> {
  private final FormData formData;
  private volatile Map<String, Collection<String>> map;

  public FormDataMap(FormData formData) {
    this.formData = formData;
  }

  private Map<String, Collection<String>> getMap() {
    if (map != null) {
      return map;
    }

    Map<String, Collection<String>> localMap = new HashMap<>();
    for (String key : formData) {
      Deque<FormData.FormValue> formValues = formData.get(key);
      List<String> values = new ArrayList<>(formValues.size());
      for (FormData.FormValue formValue : formValues) {
        if (!formValue.isFile()) {
          values.add(formValue.getValue());
        }
      }
      localMap.put(key, values);
    }

    return (map = localMap);
  }

  @Override
  public int size() {
    return getMap().size();
  }

  @Override
  public boolean isEmpty() {
    return getMap().isEmpty();
  }

  @Override
  public boolean containsKey(Object key) {
    return getMap().containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return getMap().containsValue(value);
  }

  @Override
  public Collection<String> get(Object key) {
    return getMap().get(key);
  }

  @Override
  public Collection<String> put(String key, Collection<String> value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<String> remove(Object key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putAll(Map<? extends String, ? extends Collection<String>> m) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> keySet() {
    return getMap().keySet();
  }

  @Override
  public Collection<Collection<String>> values() {
    return getMap().values();
  }

  @Override
  public Set<Entry<String, Collection<String>>> entrySet() {
    return getMap().entrySet();
  }
}
