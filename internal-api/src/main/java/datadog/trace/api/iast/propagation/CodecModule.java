package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import java.net.URI;
import java.net.URL;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface CodecModule extends IastModule {

  void onUrlDecode(@Nonnull String value, @Nullable String encoding, @Nonnull String result);

  void onUrlEncode(@Nonnull String value, @Nullable String encoding, @Nonnull String result);

  void onUriCreate(@Nonnull URI result, Object... args);

  void onUrlCreate(@Nonnull URL result, Object... args);

  void onStringFromBytes(
      @Nonnull byte[] value,
      int offset,
      int length,
      @Nullable String charset,
      @Nonnull String result);

  void onStringGetBytes(@Nonnull String value, @Nullable String charset, @Nonnull byte[] result);

  void onBase64Encode(@Nullable byte[] value, @Nullable byte[] result);

  void onBase64Decode(@Nullable byte[] value, @Nullable byte[] result);
}
