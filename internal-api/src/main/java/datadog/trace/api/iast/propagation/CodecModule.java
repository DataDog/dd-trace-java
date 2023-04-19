package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface CodecModule extends IastModule {

  void onUrlDecode(@Nonnull String value, @Nullable String encoding, @Nonnull String result);

  void onStringFromBytes(@Nonnull byte[] value, @Nullable String charset, @Nonnull String result);

  void onStringGetBytes(@Nonnull String value, @Nullable String charset, @Nonnull byte[] result);

  void onBase64Encode(@Nullable byte[] value, @Nullable byte[] result);

  void onBase64Decode(@Nullable byte[] value, @Nullable byte[] result);
}
