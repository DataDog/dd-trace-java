package datadog.trace.mlt;

/** Represents the current profiling session Call close method to end the profiling session */
public interface Session {
  /** Ends the current profiling session and returns the serialized profiling data */
  byte[] close();
}
