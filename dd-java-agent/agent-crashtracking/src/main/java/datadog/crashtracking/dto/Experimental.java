package datadog.crashtracking.dto;

import java.util.Map;
import java.util.Objects;

public final class Experimental {
  public final Map<String, String> ucontext;

  public Experimental(Map<String, String> ucontext) {
    this.ucontext = ucontext;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Experimental)) return false;
    Experimental that = (Experimental) o;
    return Objects.equals(ucontext, that.ucontext);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ucontext);
  }
}
