package datadog.trace.api.iast.taint;

public interface TaintedObject {

  Object get();

  Range[] getRanges();

  void setRanges(final Range[] ranges);
}
