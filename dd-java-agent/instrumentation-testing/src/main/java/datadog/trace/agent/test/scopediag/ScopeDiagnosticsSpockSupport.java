package datadog.trace.agent.test.scopediag;

/** Receives failures intercepted around the Spock suite-setup lifecycle. */
public interface ScopeDiagnosticsSpockSupport {
  void onSuiteSetupFailure() throws Throwable;
}
