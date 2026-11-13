package com.sap.conn.jco.ext;

import com.sap.jdsr.writer.DsrIPassport;

/** Stub of {@code com.sap.conn.jco.ext.ClientPassportManager} — see the sap-jco-stubs module. */
public interface ClientPassportManager {
  DsrIPassport callStarted(int callId, String destination, String function);

  void callFinished(int callId, long bytesSent, long bytesReceived);
}
