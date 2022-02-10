package datadog.trace.agent.tooling.bytebuddy.matcher.jfr;

import java.io.Closeable;

public interface MatchingEvent extends Closeable {

  MatchingEvent NOOP =
      new MatchingEvent() {
        @Override
        public void setMatched(boolean matched) {}

        @Override
        public void close() {}
      };

  void setMatched(boolean matched);

  void close();
}
