package com.sap.conn.jco.server;

import com.sap.conn.jco.AbapClassException;
import com.sap.conn.jco.AbapException;
import com.sap.conn.jco.JCoFunction;

/**
 * Stub of {@code com.sap.conn.jco.server.JCoServerFunctionHandler} — see the sap-jco-stubs module.
 */
public interface JCoServerFunctionHandler {
  void handleRequest(JCoServerContext serverCtx, JCoFunction function)
      throws AbapException, AbapClassException;
}
