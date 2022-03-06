package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

/**
 * Re-writes a sequence of matchers, so they can be evaluated asynchronously.
 *
 * <p>The initial matcher in the sequence triggers evaluation of all the original matchers in a
 * separate thread. Subsequent matchers in the sequence then use the results of that asynchronous
 * matching.
 *
 * <p>This assumes that the initial matcher is always called first for a given set of parameters.
 */
public class AsyncMatching {
  private final List<AgentBuilder.RawMatcher> matchers = new ArrayList<>();

  private final ThreadLocal<BitSet> localMatch =
      new ThreadLocal<BitSet>() {
        @Override
        protected BitSet initialValue() {
          return new BitSet();
        }
      };

  public AgentBuilder.RawMatcher makeAsync(AgentBuilder.RawMatcher matcher) {
    int index = matchers.size();
    matchers.add(matcher);
    return 0 == index ? new RootMatcher() : new NextMatcher(index);
  }

  class RootMatcher implements AgentBuilder.RawMatcher {
    @Override
    public boolean matches(
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain) {
      BitSet matched = localMatch.get();
      matched.clear();
      for (int i = 0, size = matchers.size(); i < size; i++) {
        if (matchers
            .get(i)
            .matches(typeDescription, classLoader, module, classBeingRedefined, protectionDomain)) {
          matched.set(i);
        }
      }
      return matched.get(0);
    }
  }

  class NextMatcher implements AgentBuilder.RawMatcher {
    private final int index;

    NextMatcher(int index) {
      this.index = index;
    }

    @Override
    public boolean matches(
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain) {
      return localMatch.get().get(index);
    }
  }
}
