package datadog.trace.core.serialization.msgpack;

import datadog.trace.core.StringTables;

public class EncodingCachingStrategies {

  public static final EncodingCache CONSTANT_KEYS = new ConstantKeys();
  public static final EncodingCache CONSTANT_TAGS = new ConstantTags();
  public static final EncodingCache NO_CACHING = new NoCaching();

  private static final class ConstantTags implements EncodingCache {

    @Override
    public byte[] encode(CharSequence s) {
      return StringTables.getTagBytesUTF8(s);
    }
  }

  private static final class ConstantKeys implements EncodingCache {

    @Override
    public byte[] encode(CharSequence s) {
      return StringTables.getKeyBytesUTF8(s);
    }
  }

  private static final class NoCaching implements EncodingCache {

    @Override
    public byte[] encode(CharSequence s) {
      return null;
    }
  }
}
