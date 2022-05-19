package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnoresMatcher;
import datadog.trace.agent.tooling.context.FieldBackedContextProvider;
import datadog.trace.agent.tooling.context.InstrumentationContextProvider;
import datadog.trace.agent.tooling.matchercache.ClassMatchers;
import datadog.trace.agent.tooling.matchercache.MatcherCacheBuilder;
import datadog.trace.agent.tooling.matchercache.MatcherCacheFileBuilder;
import datadog.trace.agent.tooling.matchercache.MatcherCacheFileBuilderParams;
import datadog.trace.agent.tooling.matchercache.classfinder.ClassFinder;
import datadog.trace.api.Platform;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;
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
    boolean enableAllInstrumenters = true;
    ClassMatchers classMatchers = AllClassMatchers.create(enableAllInstrumenters);
    MatcherCacheBuilder matcherCacheBuilder =
        new MatcherCacheBuilder(classMatchers, Platform.JAVA_VERSION.major, agentVersion);

    ClassFinder classFinder = new ClassFinder();
    MatcherCacheFileBuilder matcherCacheFileBuilder =
        new MatcherCacheFileBuilder(classFinder, matcherCacheBuilder);
    matcherCacheFileBuilder.buildMatcherCacheFile(params);
  }

  private static final class AllClassMatchers implements ClassMatchers {
    public static AllClassMatchers create(boolean enableAllInstrumenters) {
      DDElementMatchers.registerAsSupplier();

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

      return new AllClassMatchers(enableAllInstrumenters, instrumenters);
    }

    private final boolean enableAllInstrumenters;
    private final Iterable<Instrumenter> instrumenters;
    private final Map<Instrumenter, AdditionalInstrumentationMatcherCollector>
        additionalMatchersMap;

    private AllClassMatchers(boolean enableAllInstrumenters, Iterable<Instrumenter> instrumenters) {
      this.enableAllInstrumenters = enableAllInstrumenters;
      this.instrumenters = instrumenters;
      this.additionalMatchersMap = new HashMap<>();
      for (Instrumenter instr : instrumenters) {
        if (instr instanceof Instrumenter.Default) {
          InstrumentationContextProvider instrumentationContextProvider =
              FieldBackedContextProvider.contextProvider((Instrumenter.Default) instr, true);
          if (instrumentationContextProvider instanceof FieldBackedContextProvider) {
            AdditionalInstrumentationMatcherCollector matcherCollector =
                new AdditionalInstrumentationMatcherCollector();
            instrumentationContextProvider.additionalInstrumentation(matcherCollector);
            additionalMatchersMap.put(instr, matcherCollector);
          }
        }
      }
    }

    @Override
    public boolean isGloballyIgnored(String fullClassName, boolean skipAdditionalIgnores) {
      return GlobalIgnoresMatcher.isIgnored(fullClassName, skipAdditionalIgnores);
    }

    @Override
    public String matchingIntrumenters(TypeDescription typeDescription) {
      Set<String> result = allMatchingInstrumenters(typeDescription);
      if (result.isEmpty()) {
        return null;
      }
      return result.toString();
    }

    private Set<String> allMatchingInstrumenters(TypeDescription typeDescription) {
      Set<String> result = new HashSet<>();
      for (Instrumenter instr : instrumenters) {
        String instrName = instr.getClass().getSimpleName();
        ElementMatcher<? super TypeDescription> typeMatcher =
            AgentTransformerBuilder.typeMatcher(instr, !enableAllInstrumenters);
        if (typeMatcher != null) {
          if (typeMatcher.matches(typeDescription)) {
            result.add(instrName);
          }
        }
        AdditionalInstrumentationMatcherCollector additionalMatchers =
            additionalMatchersMap.get(instr);
        if (additionalMatchers != null) {
          if (additionalMatchers.matches(typeDescription)) {
            result.add(instrName);
          }
        }
      }
      return result;
    }

    private static final class AdditionalInstrumentationMatcherCollector
        implements AgentBuilder.Identified.Extendable {

      private static final class UnexpectedAgentBuilderMethodUse
          extends UnsupportedOperationException {
        public UnexpectedAgentBuilderMethodUse() {
          super(
              "Unexpected AgentBuilder method use. Probably additionalInstrumentation has changed. Adjust MatcherCollector to handle it properly.");
        }
      }

      private static final class MatcherSet {
        private final ElementMatcher<? super TypeDescription> typeMatcher;
        private ElementMatcher<? super TypeDescription> andTypeMatcher;
        private RawMatcher andRawMatcher;

        public MatcherSet(ElementMatcher<? super TypeDescription> typeMatcher) {
          this.typeMatcher = typeMatcher;
        }

        public boolean matches(TypeDescription typeDescription) {
          return (typeMatcher != null && typeMatcher.matches(typeDescription))
              && (andTypeMatcher != null && andTypeMatcher.matches(typeDescription))
              && (andRawMatcher != null
                  && andRawMatcher.matches(typeDescription, null, null, null, null));
        }

        public void setAndTypeMatcher(ElementMatcher<? super TypeDescription> typeMatcher) {
          if (this.andTypeMatcher != null) {
            throw new IllegalStateException("Expected typeMatcher = null");
          }
          this.andTypeMatcher = typeMatcher;
        }

        public void setAndRawMatcher(RawMatcher rawMatcher) {
          if (this.andRawMatcher != null) {
            throw new IllegalStateException("Expected rawMatcher = null");
          }
          this.andRawMatcher = rawMatcher;
        }
      }

      private final List<MatcherSet> matcherSets = new ArrayList<>();
      private MatcherSet currentMatcherSet;

      public boolean matches(TypeDescription typeDescription) {
        for (MatcherSet ms : matcherSets) {
          if (ms.matches(typeDescription)) {
            return true;
          }
        }
        return false;
      }

      final class NarrowableMatcherCollector implements Narrowable {
        @Override
        public Extendable transform(Transformer transformer) {
          matcherSets.add(currentMatcherSet);
          currentMatcherSet = null;
          return AdditionalInstrumentationMatcherCollector.this;
        }

        @Override
        public Narrowable and(ElementMatcher<? super TypeDescription> typeMatcher) {
          currentMatcherSet.setAndTypeMatcher(typeMatcher);
          return this;
        }

        @Override
        public Narrowable and(
            ElementMatcher<? super TypeDescription> typeMatcher,
            ElementMatcher<? super ClassLoader> classLoaderMatcher) {
          throw new UnexpectedAgentBuilderMethodUse();
        }

        @Override
        public Narrowable and(
            ElementMatcher<? super TypeDescription> typeMatcher,
            ElementMatcher<? super ClassLoader> classLoaderMatcher,
            ElementMatcher<? super JavaModule> moduleMatcher) {
          throw new UnexpectedAgentBuilderMethodUse();
        }

        @Override
        public Narrowable and(RawMatcher rawMatcher) {
          currentMatcherSet.setAndRawMatcher(rawMatcher);
          return this;
        }

        @Override
        public Narrowable or(ElementMatcher<? super TypeDescription> typeMatcher) {
          throw new UnexpectedAgentBuilderMethodUse();
        }

        @Override
        public Narrowable or(
            ElementMatcher<? super TypeDescription> typeMatcher,
            ElementMatcher<? super ClassLoader> classLoaderMatcher) {
          throw new UnexpectedAgentBuilderMethodUse();
        }

        @Override
        public Narrowable or(
            ElementMatcher<? super TypeDescription> typeMatcher,
            ElementMatcher<? super ClassLoader> classLoaderMatcher,
            ElementMatcher<? super JavaModule> moduleMatcher) {
          throw new UnexpectedAgentBuilderMethodUse();
        }

        @Override
        public Narrowable or(RawMatcher rawMatcher) {
          throw new UnexpectedAgentBuilderMethodUse();
        }
      }

      @Override
      public AgentBuilder asTerminalTransformation() {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(ByteBuddy byteBuddy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(Listener listener) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(CircularityLock circularityLock) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(PoolStrategy poolStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(LocationStrategy locationStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(TypeStrategy typeStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(InitializationStrategy initializationStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public RedefinitionListenable.WithoutBatchStrategy with(
          RedefinitionStrategy redefinitionStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(LambdaInstrumentationStrategy lambdaInstrumentationStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(DescriptionStrategy descriptionStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(FallbackStrategy fallbackStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(ClassFileBufferStrategy classFileBufferStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(InstallationListener installationListener) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(InjectionStrategy injectionStrategy) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder with(TransformerDecorator transformerDecorator) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder enableNativeMethodPrefix(String prefix) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder disableNativeMethodPrefix() {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder disableClassFormatChanges() {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder warmUp(Class<?>... type) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder warmUp(Collection<Class<?>> types) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder assureReadEdgeTo(Instrumentation instrumentation, Class<?>... type) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder assureReadEdgeTo(Instrumentation instrumentation, JavaModule... module) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder assureReadEdgeTo(
          Instrumentation instrumentation, Collection<? extends JavaModule> modules) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder assureReadEdgeFromAndTo(
          Instrumentation instrumentation, Class<?>... type) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder assureReadEdgeFromAndTo(
          Instrumentation instrumentation, JavaModule... module) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public AgentBuilder assureReadEdgeFromAndTo(
          Instrumentation instrumentation, Collection<? extends JavaModule> modules) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Narrowable type(ElementMatcher<? super TypeDescription> typeMatcher) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Narrowable type(
          ElementMatcher<? super TypeDescription> typeMatcher,
          ElementMatcher<? super ClassLoader> classLoaderMatcher) {

        if (currentMatcherSet != null) {
          throw new IllegalStateException("currentMatcherSet != null");
        }
        currentMatcherSet = new MatcherSet(typeMatcher);
        return new NarrowableMatcherCollector();
      }

      @Override
      public Narrowable type(
          ElementMatcher<? super TypeDescription> typeMatcher,
          ElementMatcher<? super ClassLoader> classLoaderMatcher,
          ElementMatcher<? super JavaModule> moduleMatcher) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Narrowable type(RawMatcher matcher) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Ignored ignore(ElementMatcher<? super TypeDescription> typeMatcher) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Ignored ignore(
          ElementMatcher<? super TypeDescription> typeMatcher,
          ElementMatcher<? super ClassLoader> classLoaderMatcher) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Ignored ignore(
          ElementMatcher<? super TypeDescription> typeMatcher,
          ElementMatcher<? super ClassLoader> classLoaderMatcher,
          ElementMatcher<? super JavaModule> moduleMatcher) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Ignored ignore(RawMatcher rawMatcher) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public ClassFileTransformer makeRaw() {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public ResettableClassFileTransformer installOn(Instrumentation instrumentation) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public ResettableClassFileTransformer installOnByteBuddyAgent() {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public ResettableClassFileTransformer patchOn(
          Instrumentation instrumentation, ResettableClassFileTransformer classFileTransformer) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public ResettableClassFileTransformer patchOnByteBuddyAgent(
          ResettableClassFileTransformer classFileTransformer) {
        throw new UnexpectedAgentBuilderMethodUse();
      }

      @Override
      public Extendable transform(Transformer transformer) {
        throw new UnexpectedAgentBuilderMethodUse();
      }
    }
  }
}
