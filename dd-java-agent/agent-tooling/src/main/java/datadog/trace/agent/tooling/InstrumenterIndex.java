package datadog.trace.agent.tooling;

import static java.util.Collections.emptyList;

public final class InstrumenterIndex {

  public static InstrumenterIndex readIndex() {
    return new InstrumenterIndex();
  }

  public Iterable<? extends InstrumenterModule> modules() {
    return emptyList();
  }

  public int instrumentationCount() {
    return 0;
  }

  public int transformationCount() {
    return 0;
  }

  public int instrumentationId(InstrumenterModule module) {
    return -1;
  }

  public int transformationId(Instrumenter member) {
    return -1;
  }
}
