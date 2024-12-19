package datadog.trace.api.iast.taint;

import javax.annotation.Nullable;

public interface Source {

  byte getOrigin();

  @Nullable
  String getName();

  @Nullable
  String getValue();

  Source attachValue(Object newValue);

  boolean isReference();

  Object getRawValue();
}
