package com.datadog.debugger;

public class CapturedSnapshot31 {

  public static int main(String arg) throws Exception {
    if ("uncaughtException".equals(arg)) {
      return new CapturedSnapshot31().uncaughtException(arg);
    }
    if ("localScopes".equals(arg)) {
      return new CapturedSnapshot31().localScopes(arg);
    }
    if ("deepScopes".equals(arg)) {
      return new CapturedSnapshot31().deepScopes(arg);
    }
    return 0;
  }

  private int uncaughtException(String arg) {
    String varStr = arg + "foo";
    if (varStr.endsWith("foo")) {
      throw new RuntimeException("oops");
    }
    int len = varStr.length();
    return len;
  }

  private int localScopes(String arg) {
    if ("1".equals(arg)) {
      String localVar = "foo";
      return localVar.length();
    } else {
      int localVar = 42;
      return localVar;
    }
  }

  private int deepScopes(String arg) {
    int localVarL0 = 0;
    if ("deepScopes".equals(arg)) {
      int localVarL1 = 1;
      while (localVarL1 > 0) {
        for (int localVarL2 = 2; localVarL2 < 3; localVarL2++) {
          int localVarL3 = 3;
          switch (localVarL3) {
            case 1: {
              int localVarL4 = 1;
              return localVarL4;
            }
            case 2: {
              int localVarL4 = 2;
              return localVarL4;
            }
            default: {
              int localVarL4 = 4;
              return localVarL4;
            }
          }
        }
      }
    }
    return localVarL0;
  }
}
