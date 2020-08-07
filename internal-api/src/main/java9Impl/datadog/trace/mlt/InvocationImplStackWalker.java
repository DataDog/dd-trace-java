package datadog.trace.mlt;

import static datadog.trace.mlt.Invocation.INVOCATION_OFFSET;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Invocation caller implementation based on the StackWalker API. Works only for Java 9+. */
@Slf4j
final class InvocationImplStackWalker implements Invocation.Impl {
  private static class FrameRef {
    final StackWalker.StackFrame frame;
    final int depth;

    FrameRef(StackWalker.StackFrame frame) {
      this(frame, 1);
    }

    FrameRef(StackWalker.StackFrame frame, int depth) {
      this.frame = frame;
      this.depth = depth;
    }

    StackWalker.StackFrame getFrame() {
      return frame;
    }
  }
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
                    .map(FrameRef::new)
                    .reduce((a, b) -> new FrameRef(b.frame, a.depth + b.depth))
                    .filter(f -> f.depth == INVOCATION_OFFSET + offset)
                    .map(FrameRef::getFrame)
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
