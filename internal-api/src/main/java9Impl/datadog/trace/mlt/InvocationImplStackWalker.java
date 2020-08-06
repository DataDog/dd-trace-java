package datadog.trace.mlt;

import static datadog.trace.mlt.Invocation.INVOCATION_OFFSET;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Invocation caller implementation based on the StackWalker API. Works only for Java 9+. */
@Slf4j
final class InvocationImplStackWalker implements Invocation.Impl {
  @Override
  public Invocation.Caller getCaller(int offset) {
    if (offset < 0) {
      throw new IllegalArgumentException();
    }
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    .limit(INVOCATION_OFFSET + offset)
                    .reduce((a, b) -> b)
                    .map(f -> new Invocation.Caller(f.getClassName(), f.getMethodName())))
        .orElse(null);
  }

  @Override
  public List<Invocation.Caller> getCallers() {
    final List<Invocation.Caller> callers = new ArrayList<>();
    StackWalker.getInstance()
        .forEach(f -> callers.add(new Invocation.Caller(f.getClassName(), f.getMethodName())));
    return callers.subList(INVOCATION_OFFSET, callers.size());
  }
}
