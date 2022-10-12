package datadog.trace.plugin.csi;

import javax.annotation.Nonnull;

public interface Validatable {

  void validate(@Nonnull ValidationContext context);
}
