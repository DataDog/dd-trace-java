package com.sap.jdsr.writer;

/**
 * Stub of SAP's jDSR passport interface. JCo's client/server passport managers return and receive
 * this type, but JCo itself does not ship it — the hosting application is expected to provide it.
 * The sap-jco instrumentation ships a real, injectable copy of this interface (see the matching
 * class in the sap-jco-3.0 module); this stub exists only so the instrumentation compiles.
 */
public interface DsrIPassport {
  void clear();

  String getCompName();

  byte[] getConnectionIdBytes();

  byte[] getNetPassport();

  byte[] getRootContextIdBytes();

  String getTransId();

  boolean isValid();

  boolean setByNetPassport(byte[] netPassport);

  void setConnectionCounter(int counter);

  void setConnectionIdBytes(byte[] connectionId);
}
