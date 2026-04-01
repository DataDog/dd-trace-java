package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Experimental {
  public final Map<String, String> ucontext;

  @Json(name = "runtime_args")
  public final List<String> runtimeArgs;

  public Experimental(Map<String, String> ucontext) {
    this(ucontext, null);
  }

  public Experimental(Map<String, String> ucontext, List<String> runtimeArgs) {
    this.ucontext = ucontext;
    this.runtimeArgs = runtimeArgs;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Experimental)) return false;
    Experimental that = (Experimental) o;
    return Objects.equals(ucontext, that.ucontext) && Objects.equals(runtimeArgs, that.runtimeArgs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ucontext, runtimeArgs);
  }
}
