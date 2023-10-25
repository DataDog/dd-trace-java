package com.datadog.debugger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class CapturedSnapshot22 {
  private int[] intArray = new int[] {0, 1,2, 3,4, 5, 6, 7, 8, 9};
  private String[] strArray = new String[] {"foo", "bar", "foobar"};
  private ArrayList<String> strList = new ArrayList<>(Arrays.asList("foo", "bar", "foobar"));
  private HashSet<String> strSet = new HashSet<>(Arrays.asList("foo", "bar", "foobar"));
  private HashMap<String, String> strMap = new HashMap<>();
  {
    strMap.put("foo1", "bar1");
    strMap.put("foo2", "bar2");
    strMap.put("foo3", "bar3");
  }


  private int doit(String arg) {
    return 42;
  }

  public static int main(String arg) {
    CapturedSnapshot22 cs22 = new CapturedSnapshot22();
    return cs22.doit(arg);

  }
}
