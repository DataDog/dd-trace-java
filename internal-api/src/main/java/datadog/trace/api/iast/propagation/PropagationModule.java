package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.Taintable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

  void taint(byte source, @Nullable String name, @Nullable String value);

  void taint(@Nullable Object ctx_, byte source, @Nullable String name, @Nullable String value);

  void taint(byte origin, @Nullable Object... toTaint);

  void taint(byte origin, @Nullable Collection<Object> toTaint);

  void taint(byte origin, @Nullable String name, @Nullable String value, @Nullable Taintable t);

  boolean isTainted(@Nullable Object obj);

  Taintable.Source firstTaintedSource(@Nullable Object input);
}
