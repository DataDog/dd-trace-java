package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnoresMatcher;
import datadog.trace.agent.tooling.context.FieldBackedContextProvider;
import datadog.trace.agent.tooling.matchercache.ClassMatchers;
import datadog.trace.agent.tooling.matchercache.MatcherCacheBuilder;
import datadog.trace.agent.tooling.matchercache.MatcherCacheFileBuilder;
import datadog.trace.agent.tooling.matchercache.MatcherCacheFileBuilderParams;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import datadog.trace.api.Platform;
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

  public static void run(File bootstrapFile, String agentVersion, String... args) {
    MatcherCacheFileBuilderParams params;
    try {
      params = MatcherCacheFileBuilderParams.parseArgs(args).withDDJavaTracerJar(bootstrapFile);
    } catch (IllegalArgumentException e) {
      System.err.println("Failed to parse params: " + e);
      MatcherCacheFileBuilderParams.printHelp();
      return;
    }
    ClassFinder classFinder = new ClassFinder();
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(Platform.JAVA_VERSION.major, agentVersion);
    boolean enableAllInstrumenters = true;
    boolean skipAdditionalIgnores = false;
    ClassMatchers classMatchers =
        AllClassMatchers.create(enableAllInstrumenters, skipAdditionalIgnores);
    MatcherCacheFileBuilder matcherCacheFileBuilder =
        new MatcherCacheFileBuilder(classFinder, matcherCacheBuilder, classMatchers);
    matcherCacheFileBuilder.buildMatcherCacheFile(params);
  }

  private static final class AllClassMatchers implements ClassMatchers {
    public static ClassMatchers create(
        boolean enableAllInstrumenters, boolean skipAdditionalIgnores) {
      final ArrayList<Instrumenter> instrumenters = new ArrayList<>();
      Instrumenter.TransformerBuilder intrumenterCollector =
          new Instrumenter.TransformerBuilder() {
            @Override
            public void applyInstrumentation(Instrumenter.HasAdvice hasAdvice) {
              if (hasAdvice instanceof Instrumenter) {
                instrumenters.add((Instrumenter) hasAdvice);
                log.debug("Found instrumenter: " + hasAdvice.getClass());
              }
            }
          };

      ServiceLoader<Instrumenter> loader =
          ServiceLoader.load(Instrumenter.class, Instrumenter.class.getClassLoader());

      // Collect all instrumenters
      for (Instrumenter instr : loader) {
        instr.instrument(intrumenterCollector);
      }

      if (enableAllInstrumenters) {
        // Enable default instrumenters
        try {
          Field enabledField = Instrumenter.Default.class.getDeclaredField("enabled");
          enabledField.setAccessible(true);
          for (Instrumenter instr : instrumenters) {
            if (instr instanceof Instrumenter.Default) {
              try {
                Object enabled = enabledField.get(instr);
                if (Boolean.FALSE.equals(enabled)) {
                  log.info("Enabling disabled instrumentation: " + instr.getClass());
                  enabledField.setBoolean(instr, true);
                }
              } catch (IllegalAccessException e) {
                log.error("Could not enable instrumentation", e);
              }
            }
          }
        } catch (NoSuchFieldException e) {
          log.error("Could not enable instrumentations", e);
        }
      }

      return new AllClassMatchers(enableAllInstrumenters, instrumenters, skipAdditionalIgnores);
    }

    private final boolean enableAllInstrumenters;
    private final Iterable<Instrumenter> instrumenters;
    private final boolean skipAdditionalIgnores;

    private AllClassMatchers(
        boolean enableAllInstrumenters,
        Iterable<Instrumenter> instrumenters,
        boolean skipAdditionalIgnores) {
      this.enableAllInstrumenters = enableAllInstrumenters;
      this.instrumenters = instrumenters;
      this.skipAdditionalIgnores = skipAdditionalIgnores;
    }

    @Override
    public boolean matchesAny(Class<?> cl) {
      TypeDescription typeDescription = TypeDescription.ForLoadedType.of(cl);
      Instrumenter instr = firstMatching(typeDescription);
      boolean result = instr != null;
      if (result) {
        log.debug("{} matched by {}", typeDescription.getActualName(), instr.getClass());
      }
      return result;
    }

    @Override
    public boolean isGloballyIgnored(String fqcn) {
      boolean ignored = GlobalIgnoresMatcher.isIgnored(fqcn, skipAdditionalIgnores);
      log.debug("{} ignored = {}", fqcn, ignored);
      return ignored;
    }

    public Instrumenter firstMatching(TypeDescription typeDescription) {
      for (Instrumenter instr : instrumenters) {
        ElementMatcher<? super TypeDescription> typeMatcher =
            AgentTransformerBuilder.typeMatcher(instr, !enableAllInstrumenters);
        if (typeMatcher != null) {
          if (typeMatcher.matches(typeDescription)) {
            return instr;
          }
        }
        if (instr instanceof Instrumenter.Default) {
          if (FieldBackedContextProvider.typeMatcher((Instrumenter.Default) instr)
              .matches(typeDescription)) {
            return instr;
          }
        }
      }
      return null;
    }
  }
}
