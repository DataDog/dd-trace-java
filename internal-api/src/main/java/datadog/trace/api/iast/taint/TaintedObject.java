package datadog.trace.api.iast.taint;

public interface TaintedObject {

  Object get();

  Object[] getRanges();

  void setRanges(final Object[] ranges);
}
