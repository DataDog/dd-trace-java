import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.telemetry.Verbosity;

public class MockCallSitesWithTelemetry
    implements CallSites, IastCallSites, IastCallSites.HasTelemetry {

  private Verbosity verbosity;

  @Override
  public void setVerbosity(Verbosity verbosity) {
    this.verbosity = verbosity;
  }

  public Verbosity getVerbosity() {
    return verbosity;
  }

  @Override
  public void accept(final Container container) {}
}
