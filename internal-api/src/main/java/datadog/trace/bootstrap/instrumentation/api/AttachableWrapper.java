package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Cache interface for OT/OTel wrappers used by TypeConverters to avoid extra allocations. */
public interface AttachableWrapper {

  /** Attaches a OT/OTel wrapper to a tracer object. */
  void attachWrapper(@Nonnull SpanWrapper wrapper);

  /** Returns an attached OT/OTel wrapper or null. */
  @Nullable
  SpanWrapper getWrapper();
}
