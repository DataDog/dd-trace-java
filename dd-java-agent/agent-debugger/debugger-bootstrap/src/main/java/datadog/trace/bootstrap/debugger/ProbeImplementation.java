package datadog.trace.bootstrap.debugger;

import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import java.util.List;

/** Probe information associated with a snapshot */
public interface ProbeImplementation {
  ProbeImplementation UNKNOWN = new NoopProbeImplementation("UNKNOWN", ProbeLocation.UNKNOWN);

  String getId();

  ProbeLocation getLocation();

  String getStrTags();

  void evaluate(CapturedContext context, CapturedContext.Status status);

  void commit(
      CapturedContext entryContext,
      CapturedContext exitContext,
      List<CapturedContext.CapturedThrowable> caughtExceptions);

  void commit(CapturedContext lineContext, int line);

  MethodLocation getEvaluateAt();

  boolean isCaptureSnapshot();

  boolean hasCondition();

  CapturedContext.Status createStatus();

  class NoopProbeImplementation implements ProbeImplementation {
    private final String id;
    private final int version;
    private final ProbeLocation location;
    private final MethodLocation evaluateAt;
    private final boolean captureSnapshot;
    private final DebuggerScript<Boolean> script;
    private final String tags;

    public NoopProbeImplementation(String id, ProbeLocation location) {
      this(id, 0, location, MethodLocation.DEFAULT, true, null, null);
    }

    public NoopProbeImplementation(
        String id,
        int version,
        ProbeLocation location,
        MethodLocation evaluateAt,
        boolean captureSnapshot,
        DebuggerScript<Boolean> script,
        String tags) {
      this.id = id;
      this.version = version;
      this.location = location;
      this.evaluateAt = evaluateAt;
      this.captureSnapshot = captureSnapshot;
      this.script = script;
      this.tags = tags;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public ProbeLocation getLocation() {
      return location;
    }

    @Override
    public String getStrTags() {
      return null;
    }

    @Override
    public void evaluate(CapturedContext context, CapturedContext.Status status) {}

    @Override
    public void commit(
        CapturedContext entryContext,
        CapturedContext exitContext,
        List<CapturedContext.CapturedThrowable> caughtExceptions) {}

    @Override
    public void commit(CapturedContext lineContext, int line) {}

    @Override
    public MethodLocation getEvaluateAt() {
      return evaluateAt;
    }

    @Override
    public boolean isCaptureSnapshot() {
      return captureSnapshot;
    }

    @Override
    public boolean hasCondition() {
      return script != null;
    }

    @Override
    public CapturedContext.Status createStatus() {
      return CapturedContext.Status.EMPTY_CAPTURING_STATUS;
    }
  }
}
