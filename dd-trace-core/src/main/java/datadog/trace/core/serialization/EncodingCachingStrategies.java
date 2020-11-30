package datadog.trace.core.serialization;

import datadog.trace.core.StringTables;

public class EncodingCachingStrategies {

  public static final EncodingCache CONSTANT_KEYS = new ConstantKeys();
  public static final EncodingCache NO_CACHING = null;

  private static final class ConstantKeys implements EncodingCache {

    @Override
    public byte[] encode(CharSequence s) {
      return StringTables.getKeyBytesUTF8(s);
    }
  }
}
