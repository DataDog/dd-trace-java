package com.sap.conn.jco;

/** Stub of {@code com.sap.conn.jco.JCoDestination} — see the sap-jco-stubs module description. */
public interface JCoDestination {
  String getDestinationName();

  String getApplicationServerHost();

  String getSystemNumber();

  String getClient();

  String getUser();

  String getLanguage();

  String getR3Name();

  JCoAttributes getAttributes() throws JCoException;
}
