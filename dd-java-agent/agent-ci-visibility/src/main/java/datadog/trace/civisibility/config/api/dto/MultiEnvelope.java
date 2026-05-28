package datadog.trace.civisibility.config.api.dto;

import datadog.trace.civisibility.config.api.dto.response.Meta;
import java.util.Collection;
import javax.annotation.Nullable;

public final class MultiEnvelope<T> {
  public final Collection<Data<T>> data;
  @Nullable public final Meta meta;

  public MultiEnvelope(Collection<Data<T>> data, @Nullable Meta meta) {
    this.data = data;
    this.meta = meta;
  }
}
