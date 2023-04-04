package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.Taintable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface PropagationModule extends IastModule {

  void taintIfInputIsTainted(@Nullable Object toTaint, @Nullable Object input);

  void taintIfInputIsTainted(@Nullable String toTaint, @Nullable Object input);

  void taintIfInputIsTainted(
      byte origin, @Nullable String name, @Nullable String toTaint, @Nullable Object input);

  void taintIfInputIsTainted(
      byte origin,
      @Nullable String name,
      @Nullable Collection<String> toTaint,
      @Nullable Object input);

  void taintIfInputIsTainted(
      byte origin, @Nullable Collection<String> toTaint, @Nullable Object input);

  void taintIfInputIsTainted(
      byte origin, @Nullable List<Map.Entry<String, String>> toTaint, @Nullable Object input);

  void taintIfAnyInputIsTainted(@Nullable Object toTaint, @Nullable Object... inputs);

  void taint(byte origin, @Nullable String toTaint);

  void taint(byte origin, @Nullable Object... toTaint);

  void taint(byte origin, @Nullable Collection<Object> toTaint);

  void namedTaint(byte origin, @Nullable String name, @Nullable String valueToTaint);

  void namedTaint(
      @Nonnull Object ctx, byte origin, @Nullable String name, @Nullable String valueToTaint);

  void namedTaint(byte origin, @Nullable String name, @Nullable String[] toTaintArray);

  void namedTaint(byte origin, @Nullable String name, @Nullable Collection<?> toTaintCollection);

  void taintName(byte origin, @Nullable String name);

  void taintNames(byte origin, @Nullable Collection<?> toTaintCollection);

  void namedTaint(
      @Nullable Taintable t, byte origin, @Nullable String name, @Nullable String value);

  void taintNameValuesMap(byte source, @Nullable Map<String, String[]> values);

  boolean isTainted(@Nullable Object obj);
}
