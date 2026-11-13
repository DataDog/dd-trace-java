package com.sap.conn.jco;

/** Stub of {@code com.sap.conn.jco.JCoFunction} — see the sap-jco-stubs module description. */
public interface JCoFunction {
  String getName();

  void execute(JCoDestination destination) throws JCoException;

  void execute(JCoDestination destination, String tid) throws JCoException;

  void execute(JCoDestination destination, String tid, String queueName) throws JCoException;
}
