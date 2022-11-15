package datadog.trace.api.http;

import java.util.function.Supplier;
import javax.annotation.Nonnull;

public interface StoredBodySupplier extends Supplier<CharSequence> {
  @Nonnull
  CharSequence get();
}
