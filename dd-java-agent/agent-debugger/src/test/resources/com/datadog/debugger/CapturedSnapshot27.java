package com.datadog.debugger;

import java.util.HashMap;
import java.util.Map;

public class CapturedSnapshot27 {
  private HashMap<String, String> strMap = new HashMap<>();
  private Map<String, Creds> credMap = new HashMap<>();
  {
    strMap.put("foo1", "bar1");
    strMap.put("foo3", "bar3");
    credMap.put("dave", new Creds("dave", "secret456"));
  }

  private String password;
  private Creds creds;

  private int doit(String arg) {
    creds = new Creds("john", arg);
    password = arg;
    String secret = arg;
    strMap.put("password", arg);
    return 42;
  }

  public static int main(String arg) {
    CapturedSnapshot27 cs27 = new CapturedSnapshot27();
    return cs27.doit(arg);
  }

  static class Creds {
    private String user;
    private String secretCode;

    public Creds(String user, String secretCode) {
      this.user = user;
      this.secretCode = secretCode;
    }
  }
}
