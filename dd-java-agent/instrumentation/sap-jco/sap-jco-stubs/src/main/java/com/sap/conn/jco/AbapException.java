package com.sap.conn.jco;

/** Stub of {@code com.sap.conn.jco.AbapException} — see the sap-jco-stubs module description. */
public class AbapException extends JCoException {
  public AbapException(String key, String message) {
    super(0, key, message);
  }

  @Override
  public String getKey() {
    return null;
  }
}
