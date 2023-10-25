package com.datadog.debugger;

import java.util.HashMap;

public class CapturedSnapshot26 {

  private Holder<String, String> holder = new Holder<>(this);
  private HolderWithException<String, String> holderWithException = new HolderWithException<>(this);

  public static int main(String arg) {
    CapturedSnapshot26 cs26 = new CapturedSnapshot26();
    if ("exception".equals(arg)) {
      return cs26.doItException(arg);
    }
    return cs26.doit(arg);
  }

  private int doit(String arg) {
    holder.put("foo", "bar");
    return holder.size();
  }

  private int doItException(String arg) {
    holderWithException.put("foo", "bar");
    return holderWithException.size();
  }

  static class Holder<K, V> extends HashMap<K, V> {
    private final CapturedSnapshot26 capturedSnapshot26;

    public Holder(CapturedSnapshot26 capturedSnapshot26) {
      this.capturedSnapshot26 = capturedSnapshot26;
    }

    @Override
    public int size() {
      return super.size();
    }
  }

  static class HolderWithException<K, V> extends HashMap<K, V> {
    private final CapturedSnapshot26 capturedSnapshot26;

    public HolderWithException(CapturedSnapshot26 capturedSnapshot26) {
      this.capturedSnapshot26 = capturedSnapshot26;
    }

    @Override
    public int size() {
      throw new UnsupportedOperationException("not supported");
    }
  }
}
