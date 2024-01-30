package datadog.trace.api.naming;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.util.TagsHelper;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServiceNaming {
  private static final Logger LOGGER = LoggerFactory.getLogger(ServiceNaming.class);
  private UTF8BytesString current;
  private final UTF8BytesString original;
  private final boolean mutable;
  private UTF8BytesString sanitizedName;

  public ServiceNaming(final CharSequence initialName, final boolean mutable) {
    this.original = UTF8BytesString.create(initialName);
    this.mutable = mutable;
    doUpdate(original);
  }

  // this is just to ensure that updates are not running wildly. There is no consistency on the read
  // to avoid contentions.
  private synchronized void doUpdate(@Nonnull final UTF8BytesString name) {
    current = name;
    sanitizedName = UTF8BytesString.create(TagsHelper.sanitize(current.toString()));
  }

  public boolean update(@Nonnull final CharSequence name) {
    if (!mutable) {
      LOGGER.debug("Denied service name change from {} to {}", current, name);
      return false;
    }
    doUpdate(UTF8BytesString.create(name));
    LOGGER.debug("Triggered service name change from {} to {}", current, name);
    return true;
  }

  public boolean isMutable() {
    return mutable;
  }

  public UTF8BytesString getCurrent() {
    return current;
  }

  public UTF8BytesString getOriginal() {
    return original;
  }

  public UTF8BytesString getSanitizedName() {
    return sanitizedName;
  }
}
