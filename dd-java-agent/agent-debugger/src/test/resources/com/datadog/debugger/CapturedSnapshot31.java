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
    if ("overlappingSlots".equals(arg)) {
      return new CapturedSnapshot31().overlappingSlots(arg);
    }
    if (arg.startsWith("illegal")) {
      return new CapturedSnapshot31().caughtException(arg);
    }
    if ("duplicateLocalDifferentScope".equals(arg)) {
      return new CapturedSnapshot31().duplicateLocalDifferentScope(arg);
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

  private int caughtException(String arg) {
    try {
      if ("illegalState".equals(arg)) {
        throw new IllegalStateException("state");
      }
      if ("illegalArgument".equals(arg)) {
        throw new IllegalArgumentException("argument");
      }
      return -1;
    } catch (IllegalStateException ex) {
      ex.printStackTrace();
      return 0;
    } catch (IllegalArgumentException ex) {
      ex.printStackTrace();
      return 0;
    }
  }

//  LocalVariableTable:
//        Start  Length  Slot  Name   Signature
//           15      22     3 subStr   Ljava/lang/String;
//            2      41     2     i   I
//           60      36     3     i   C
//          105      10     3     i   Ljava/lang/Object;
//            0     120     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
//            0     120     1   arg   Ljava/lang/String;
//           50      70     2 subStr   Ljava/lang/String;
  private int overlappingSlots(String arg) {
    for (int i = 0; i < 10; i++) {
      String subStr = arg.substring(0, 2);
      arg += subStr.length();
    }
    String subStr = arg.substring(0, 5);
    if (subStr != null) {
      char i = arg.charAt(0);
      if (i == 0) {
        throw new IllegalArgumentException("Illegal option name '" + i + "'");
      }
    } else {
      Object i = arg.substring(3);
      System.out.println(i.toString());
    }
    return subStr.length();
  }

  private int duplicateLocalDifferentScope(String arg) {
    if (arg == null) {
      return 0;
    } else {
      if (arg.length() == 1) {
        char ch = arg.charAt(0);
        if (ch == 1) {
          throw new IllegalArgumentException("Illegal option name '" + ch + "'");
        }
      } else {
        for (char ch : arg.toCharArray()) {
          if (ch == 1) {
            throw new IllegalArgumentException("The option '" + arg + "' contains an illegal character : '" + ch + "'");
          }
        }
      }
      return arg.length();
    }
  }
}
