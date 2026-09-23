package datadog.trace.bootstrap;

/** Runtime activation state of the subsystems that can be switched on and off after start-up. */
public class ActiveSubsystems {
  public static volatile boolean APPSEC_ACTIVE;
}
