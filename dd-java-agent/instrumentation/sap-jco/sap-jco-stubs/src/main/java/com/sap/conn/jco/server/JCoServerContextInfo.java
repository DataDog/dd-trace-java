package com.sap.conn.jco.server;

import com.sap.conn.jco.JCoAttributes;

/** Stub of {@code com.sap.conn.jco.server.JCoServerContextInfo} — see the sap-jco-stubs module. */
public interface JCoServerContextInfo {
  JCoAttributes getConnectionAttributes();

  String getConnectionID();

  String getSessionID();

  String getTID();

  boolean isInTransaction();

  JCoServerCallType getCallType();

  boolean isStatefulSession();
}
