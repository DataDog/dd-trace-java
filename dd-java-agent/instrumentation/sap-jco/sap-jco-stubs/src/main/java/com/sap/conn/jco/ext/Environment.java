package com.sap.conn.jco.ext;

/**
 * Stub of {@code com.sap.conn.jco.ext.Environment} — see the sap-jco-stubs module. Only the static
 * passport-manager registration surface the instrumentation calls is modelled.
 */
public abstract class Environment {
  public static void registerClientPassportManager(ClientPassportManager manager) {}

  public static boolean isClientPassportManagerRegistered() {
    return false;
  }

  public static void registerServerPassportManager(ServerPassportManager manager) {}

  public static boolean isServerPassportManagerRegistered() {
    return false;
  }
}
