package com.sap.jdsr.writer;

/**
 * SAP's jDSR passport interface. JCo's client call path expects this exact type ({@code
 * com.sap.jdsr.writer.DsrIPassport}) to be present on its own classloader, but JCo does not ship it
 * — in a hand-instrumented app the developer has to supply it. This instrumentation ships a real
 * copy and injects it (together with {@link datadog.trace.instrumentation.sapjco.DatadogPassport}
 * and {@link datadog.trace.instrumentation.sapjco.DatadogClientPassportManager}) into whichever
 * classloader loaded JCo, so JCo can link against it and call our passport manager.
 *
 * <p>The method set is exactly the one JCo 3.1's client call path invokes (discovered with {@code
 * javap} on {@code sapjco3.jar}). Keep it in sync with the compile-time stub in the sap-jco-stubs
 * module.
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
