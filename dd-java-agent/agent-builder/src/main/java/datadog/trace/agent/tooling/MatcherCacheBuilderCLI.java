package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.matchercache.ClassMatchers;
import datadog.trace.agent.tooling.matchercache.MatcherCacheBuilder;
import datadog.trace.agent.tooling.matchercache.MatcherCacheFileBuilder;
import datadog.trace.agent.tooling.matchercache.MatcherCacheFileBuilderParams;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import datadog.trace.agent.tooling.matchercache.util.JavaVersion;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.ServiceLoader;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// called by reflection from agent-bootstrap
public final class MatcherCacheBuilderCLI {
  private static final Logger log = LoggerFactory.getLogger(MatcherCacheBuilderCLI.class);

  public static void run(File bootstrapFile, String... args) {
    MatcherCacheFileBuilderParams params;
    try {
      params = MatcherCacheFileBuilderParams.parseArgs(args).withDDJavaTracerJar(bootstrapFile);
    } catch (IllegalArgumentException e) {
      System.err.println("Failed to parse params: " + e);
      MatcherCacheFileBuilderParams.printHelp();
      return;
    }
    ClassFinder classFinder = new ClassFinder();
    MatcherCacheBuilder matcherCacheBuilder = new MatcherCacheBuilder(JavaVersion.MAJOR_VERSION);
    ClassMatchers classMatchers = AllClassMatchers.create();
    MatcherCacheFileBuilder matcherCacheFileBuilder =
        new MatcherCacheFileBuilder(classFinder, matcherCacheBuilder, classMatchers);
    matcherCacheFileBuilder.buildMatcherCacheFile(params);
  }

  // can't unit test it because Instrumenters are not observable from the unit test
  private static final class AllClassMatchers implements ClassMatchers {
    // combines all the existing matchers and enables all of them

    public static ClassMatchers create() {
      ArrayList<Instrumenter.Default> instrumenters = new ArrayList<>();
      try {
        Field enabledField = Instrumenter.Default.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);

        ServiceLoader<Instrumenter> loader =
            ServiceLoader.load(Instrumenter.class, Instrumenter.class.getClassLoader());

        for (Instrumenter instr : loader) {
          if (instr instanceof Instrumenter.Default) {
            try {
              enabledField.setBoolean(instr, true);
            } catch (IllegalAccessException e) {
              e.printStackTrace();
            }
            instrumenters.add((Instrumenter.Default) instr);
          }
        }
      } catch (NoSuchFieldException e) {
        e.printStackTrace();
      }

      return new AllClassMatchers(instrumenters);
    }

    private final Iterable<Instrumenter.Default> instrumenters;

    private AllClassMatchers(Iterable<Instrumenter.Default> instrumenters) {
      this.instrumenters = instrumenters;
    }

    @Override
    public boolean matchesAny(Class<?> cl) {
      return firstMatching(cl) != null;
    }

    public Instrumenter.Default firstMatching(Class<?> cl) {
      TypeDescription typeDescription = TypeDescription.ForLoadedType.of(cl);
      for (Instrumenter.Default instr : instrumenters) {
        ElementMatcher<? super TypeDescription> typeMatcher =
            AgentTransformerBuilder.typeMatcher(instr, true);
        if (typeMatcher != null) {
          if (typeMatcher.matches(typeDescription)) {
            return instr;
          }
        }
      }
      return null;
    }
  }
}
