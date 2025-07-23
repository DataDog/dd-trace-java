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
    if ("mixingIntAndLong".equals(arg)) {
      return new CapturedSnapshot31().mixingIntAndLong(arg);
    }
    if ("mixingIntAndChar".equals(arg)) {
      return new CapturedSnapshot31().mixingIntAndChar(arg);
    }
    if ("sameSlotAndTypeDifferentName".equals(arg)) {
      return new CapturedSnapshot31().sameSlotAndTypeDifferentName(arg);
    }
    if ("mixingIntAndRefType".equals(arg)) {
      return new CapturedSnapshot31().mixingIntAndRefType(arg);
    }
    if ("sameSlotAndNameOneReturn".equals(arg)) {
      return new CapturedSnapshot31().sameSlotAndNameOneReturn(arg);
    }
    return 0;
  }

  //  LocalVariableTable:
  //        Start  Length  Slot  Name   Signature
  //            0      46     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
  //            0      46     1   arg   Ljava/lang/String;
  //           20      26     2 varStr   Ljava/lang/String;
  //           44       2     3   len   I
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

  //  LocalVariableTable:
  //        Start  Length  Slot  Name   Signature
  //           41       6     2    ex   Ljava/lang/IllegalStateException;
  //           48       6     2    ex   Ljava/lang/IllegalArgumentException;
  //            0      54     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
  //            0      54     1   arg   Ljava/lang/String;
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

  //  LocalVariableTable:
  //        Start  Length  Slot  Name   Signature
  //           20      37     2    ch   C
  //           83      48     5    ch   C
  //            0     142     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
  //            0     142     1   arg   Ljava/lang/String;
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

  //  LocalVariableTable:
//        Start  Length  Slot  Name   Signature
//            7       7     2     i   I
//           10       4     3     j   I
//           19      25     4     i   I
//           16      31     2     l   J
//            0      47     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
//            0      47     1   arg   Ljava/lang/String;
//
// when hoisting j local var we will extend the range and then overlap to the l range. But longs
// are occupying two slots, so we need to consider l having slot 2 and 3 and prevent j to be hoisted
  private int mixingIntAndLong(String arg) {
    if (arg == null) {
      int i = 42;
      int j = 84;
      return i + j;
    } else {
      long l = 0;
      for (int i = 0; i < arg.length(); i++) {
        l += arg.charAt(i);
      }
      return (int) l;
    }
  }

  //  LocalVariableTable:
  //        Start  Length  Slot  Name   Signature
  //            7       2     2     i   I
  //           12       2     2     c   C
  //            0      14     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
  //            0      14     1   arg   Ljava/lang/String;
  //
  // when hoisting c local var we will extend the range and then overlap to the i range. But chars
  // are considered at bytecode level as ints. BUT chars are unsigned shorts and treating them as
  // signed int can lead to unexpected results (cf Character.valueOf(char) with negative value)
  private int mixingIntAndChar(String arg) {
    if (arg != null) {
      int i = -327;
      return i;
    } else {
      char c = 'a';
      return (int)c;
    }
  }


  //  LocalVariableTable:
  //        Start  Length  Slot  Name   Signature
  //            9       2     2     i   I
  //           19       5     2     o   Ljava/lang/Object;
  //            0      24     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
  //            0      24     1   arg   Ljava/lang/String;
  private int mixingIntAndRefType(String arg) {
    if (arg != null) {
      int i = arg.length();
      return i;
    } else {
      Object o = new Object();
      return o.hashCode();
    }
  }

  //  LocalVariableTable:
  //        Start  Length  Slot  Name   Signature
  //           13       5     2     p   Ljava/lang/Object;
  //           26       5     2     r   Ljava/lang/Object;
  //           39       5     2     o   Ljava/lang/Object;
  //            0      44     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
  //            0      44     1   arg   Ljava/lang/String;
  private int sameSlotAndTypeDifferentName(String arg) {
    if (arg != null) {
      if (arg.length() > 0) {
        Object p = arg;
        return arg.length();
      } else {
        Object r = new Object();
        return r.hashCode();
      }
    } else {
      Object o = new Object();
      return o.hashCode();
    }
  }

  //  LocalVariableTable:
  //        Start  Length  Slot  Name   Signature
  //            6       5     3     o   Ljava/lang/String;
  //           11       3     2 result   I
  //           22       5     3     o   Ljava/lang/Object;
  //            0      29     0  this   Lcom/datadog/debugger/CapturedSnapshot31;
  //            0      29     1   arg   Ljava/lang/String;
  //           27       2     2 result   I
  private int sameSlotAndNameOneReturn(String arg) {
    int result;
    if (arg != null) {
      String o = arg;
      result = arg.length();
    } else {
      Object o = new Object();
      result = o.hashCode();
    }
    return result;
  }
}
