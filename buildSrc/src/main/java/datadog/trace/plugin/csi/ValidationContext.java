package datadog.trace.plugin.csi;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

public interface ValidationContext extends HasErrors {
  <E> E getContextProperty(@Nonnull String name);

  void addContextProperty(@Nonnull String name, Object object);

  class BaseValidationContext extends HasErrorsImpl implements ValidationContext {

    private final Map<String, Object> context = new HashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public final <E> E getContextProperty(@Nonnull final String name) {
      return (E) context.get(name);
    }

    @Override
    public final void addContextProperty(@Nonnull final String name, final Object object) {
      context.put(name, object);
    }
  }
}
