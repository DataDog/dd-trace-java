package com.sap.conn.jco.ext;

import com.sap.jdsr.writer.DsrIPassport;

/** Stub of {@code com.sap.conn.jco.ext.ServerPassportManager} — see the sap-jco-stubs module. */
public interface ServerPassportManager {
  void requestStarted(int requestId, DsrIPassport passport, String function);

  void requestFinished(int requestId, long bytesSent, long bytesReceived);
}
