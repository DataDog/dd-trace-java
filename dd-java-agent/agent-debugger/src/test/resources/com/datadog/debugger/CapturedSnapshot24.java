package com.datadog.debugger;

import java.util.ArrayList;

public class CapturedSnapshot24 {

  private Holder<String> holder = new Holder<>(this);
  private HolderWithException<String> holderWithException = new HolderWithException<>(this);

  public static int main(String arg) {
    CapturedSnapshot24 cs24 = new CapturedSnapshot24();
    if ("exception".equals(arg)) {
      return cs24.doItException(arg);
    }
    return cs24.doit(arg);
  }

  private int doit(String arg) {
    holder.add("foo");
    return holder.size();
  }

  private int doItException(String arg) {
    holderWithException.add("foo");
    return holderWithException.size();
  }

  static class Holder<T> extends ArrayList<T> {
    private final CapturedSnapshot24 capturedSnapshot24;

    public Holder(CapturedSnapshot24 capturedSnapshot24) {
      this.capturedSnapshot24 = capturedSnapshot24;
    }

    @Override
    public int size() {
      return super.size();
    }
  }

  static class HolderWithException<T> extends ArrayList<T> {
    private final CapturedSnapshot24 capturedSnapshot24;

    public HolderWithException(CapturedSnapshot24 capturedSnapshot24) {
      this.capturedSnapshot24 = capturedSnapshot24;
    }

    @Override
    public int size() {
      throw new UnsupportedOperationException("not supported");
    }
  }
}
