package com.datadog.debugger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class CapturedSnapshot24 {

  private Holder<String> holder = new Holder<>(this);

  public static int main(String arg) {
    CapturedSnapshot24 cs24 = new CapturedSnapshot24();
    return cs24.doit(arg);
  }

  private int doit(String arg) {
    holder.add("foo");
    return holder.size();
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
}
