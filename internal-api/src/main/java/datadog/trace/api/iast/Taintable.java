package datadog.trace.api.iast;

public interface Taintable {

  Source $$DD$getSource();

  void $$DD$setSource(final Source source);

  /** Interface to isolate customer classloader from our classes */
  interface Source {
    byte getOrigin();

    String getName();

    String getValue();
  }
}
