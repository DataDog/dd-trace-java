package probe;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.Trace;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import weblogic.diagnostics.context.Correlation;
import weblogic.diagnostics.context.CorrelationFactory;

@Stateless(mappedName = "t3Probe")
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class EchoBean implements Echo {
  private static volatile Echo downstream;

  @Override
  public void prepare(String providerUrl) throws Exception {
    downstream = Lookup.echo(providerUrl);
  }

  @Override
  @Trace(operationName = "probe.read")
  public String read() {
    return CorrelationIdentifier.getTraceId() + "|" + diagnosticIds();
  }

  @Override
  @Trace(operationName = "probe.nested")
  public String nested() {
    return downstream.read() + "|" + diagnosticIds();
  }

  @Override
  public String untraced() {
    return CorrelationIdentifier.getTraceId();
  }

  @Override
  public void fail(String traceId) {
    if ("0".equals(traceId) || !traceId.equals(CorrelationIdentifier.getTraceId())) {
      throw new IllegalStateException("missing context in throwing invocation");
    }
    throw new IllegalStateException("probe failure");
  }

  private static String diagnosticIds() {
    Correlation correlation = CorrelationFactory.findCorrelation();
    return correlation == null
        ? "missing|missing"
        : correlation.getECID() + "|" + correlation.getRID();
  }
}
