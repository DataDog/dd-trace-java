package datadog.trace.bootstrap.instrumentation.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Cache interface for OT/OTel wrappers used by TypeConverters to avoid extra allocations. */
public interface AttachableSpanWrapper {

  /** Attaches a OT/OTel wrapper to a tracer object, only if it's not yet been attached. */
  void attachWrapper(@Nonnull Object wrapper);

  /** Returns an attached OT/OTel wrapper or null. */
  @Nullable
  Object getWrapper();
}
