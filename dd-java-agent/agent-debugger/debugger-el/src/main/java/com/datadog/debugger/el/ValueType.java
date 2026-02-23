package com.datadog.debugger.el;

public enum ValueType {
  OBJECT,
  BOOLEAN,
  INT,
  LONG,
  DOUBLE,
  BYTE,
  SHORT,
  CHAR,
  FLOAT;

  public static String toString(ValueType type) {
    switch (type) {
      case BOOLEAN:
      case INT:
      case LONG:
      case DOUBLE:
      case BYTE:
      case SHORT:
      case CHAR:
      case FLOAT:
        return type.name().toLowerCase();
      default:
        return Object.class.getTypeName();
    }
  }

  public static ValueType of(String type) {
    switch (type) {
      case "boolean":
        return BOOLEAN;
      case "int":
        return INT;
      case "long":
        return LONG;
      case "double":
        return DOUBLE;
      case "byte":
        return BYTE;
      case "short":
        return SHORT;
      case "char":
        return CHAR;
      case "float":
        return FLOAT;
      default:
        return OBJECT;
    }
  }
}
