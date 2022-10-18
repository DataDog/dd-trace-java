package datadog.trace.bootstrap.instrumentation.api;

public interface ContextThreadListener {
  /** Invoked when a trace first propagates to a thread */
  void onAttach();

  /** Invoked when a thread exits */
  void onDetach();
}
