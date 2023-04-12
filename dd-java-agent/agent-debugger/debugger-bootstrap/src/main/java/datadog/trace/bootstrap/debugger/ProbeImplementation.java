package datadog.trace.bootstrap.debugger;

import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import java.util.List;

/** Probe information associated with a snapshot */
public interface ProbeImplementation {
  ProbeImplementation UNKNOWN =
      new NoopProbeImplementation("UNKNOWN", Snapshot.ProbeLocation.UNKNOWN);

  String getId();

  Snapshot.ProbeLocation getLocation();

  String getStrTags();

  void evaluate(
      Snapshot.CapturedContext context,
      Snapshot.CapturedContext.Status status,
      MethodLocation methodLocation);

  void commit(
      Snapshot.CapturedContext entryContext,
      Snapshot.CapturedContext exitContext,
      List<Snapshot.CapturedThrowable> caughtExceptions);

  void commit(Snapshot.CapturedContext lineContext, int line);

  MethodLocation getEvaluateAt();

  boolean isCaptureSnapshot();

  boolean hasCondition();

  class NoopProbeImplementation implements ProbeImplementation {
    private final String id;
    private final int version;
    private final Snapshot.ProbeLocation location;
    private final MethodLocation evaluateAt;
    private final boolean captureSnapshot;
    private final DebuggerScript<Boolean> script;
    private final String tags;

    public NoopProbeImplementation(String id, Snapshot.ProbeLocation location) {
      this(id, 0, location, MethodLocation.DEFAULT, true, null, null);
    }

    public NoopProbeImplementation(
        String id,
        int version,
        Snapshot.ProbeLocation location,
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
    public Snapshot.ProbeLocation getLocation() {
      return location;
    }

    @Override
    public String getStrTags() {
      return null;
    }

    @Override
    public void evaluate(
        Snapshot.CapturedContext context,
        Snapshot.CapturedContext.Status status,
        MethodLocation methodLocation) {}

    @Override
    public void commit(
        Snapshot.CapturedContext entryContext,
        Snapshot.CapturedContext exitContext,
        List<Snapshot.CapturedThrowable> caughtExceptions) {}

    @Override
    public void commit(Snapshot.CapturedContext lineContext, int line) {}

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
  }
}
