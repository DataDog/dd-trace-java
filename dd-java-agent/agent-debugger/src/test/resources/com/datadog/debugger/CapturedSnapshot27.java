package com.datadog.debugger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class CapturedSnapshot27 {
  private HashMap<String, String> strMap = new HashMap<>();
  {
    strMap.put("foo1", "bar1");
    strMap.put("foo3", "bar3");
  }

  private String password;


  private int doit(String arg) {
    password = arg;
    String secret = arg;
    strMap.put("password", arg);
    return 42;
  }

  public static int main(String arg) {
    CapturedSnapshot27 cs27 = new CapturedSnapshot27();
    return cs27.doit(arg);

  }
}
