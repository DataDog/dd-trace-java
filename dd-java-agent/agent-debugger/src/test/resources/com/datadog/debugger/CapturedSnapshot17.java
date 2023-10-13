package com.datadog.debugger;

import java.time.Month;
import java.time.LocalDateTime;

public class CapturedSnapshot17 {
  private Object objField = "foobar";

  public static int main(String arg) {
    CapturedSnapshot17 cs17 = new CapturedSnapshot17();
    return (Integer)cs17.processWithArg(new Integer(42));
  }

  public Object processWithArg(Object obj) {
    Object result = (Integer)obj + 8;
    return result;
  }
}
