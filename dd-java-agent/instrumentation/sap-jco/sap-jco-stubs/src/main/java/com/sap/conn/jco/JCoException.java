package com.sap.conn.jco;

/** Stub of {@code com.sap.conn.jco.JCoException} — see the sap-jco-stubs module description. */
public class JCoException extends Exception {
  public JCoException(int group, String key, String message) {
    super(message);
  }

  public int getGroup() {
    return 0;
  }

  public String getKey() {
    return null;
  }

  public String getMessageText() {
    return null;
  }
}
