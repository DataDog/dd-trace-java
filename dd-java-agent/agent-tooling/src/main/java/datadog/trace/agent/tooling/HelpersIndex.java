package datadog.trace.agent.tooling;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps each {@link InstrumenterModule} to the helper classes it injects, resolved at build time so
 * the agent does not have to load every module's {@code $Muzzle} class at install to read them.
 *
 * <p>Entries are positional and share {@link InstrumenterIndex}'s module ordering (both iterate
 * {@link InstrumenterIndex#loadModules}), so a module's {@code instrumentationId} indexes into this
 * table directly.
 */
final class HelpersIndex {
  private static final Logger log = LoggerFactory.getLogger(HelpersIndex.class);

  private static final String HELPERS_INDEX_NAME = "helpers.index";

  static final ClassLoader instrumenterClassLoader = Instrumenter.class.getClassLoader();

  private final String[][] helpersByInstrumentationId;

  private HelpersIndex(String[][] helpersByInstrumentationId) {
    this.helpersByInstrumentationId = helpersByInstrumentationId;
  }

  /** Build-time-resolved helper class names for the module, or {@code null} if not indexed. */
  public String[] helperClassNames(int instrumentationId) {
    if (instrumentationId < 0 || instrumentationId >= helpersByInstrumentationId.length) {
      return null;
    }
    return helpersByInstrumentationId[instrumentationId];
  }

  public static HelpersIndex readIndex() {
    URL indexResource = instrumenterClassLoader.getResource(HELPERS_INDEX_NAME);
    if (null != indexResource) {
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(indexResource.openStream()))) {
        int moduleCount = in.readInt();
        String[][] helpers = new String[moduleCount][];
        for (int i = 0; i < moduleCount; i++) {
          String[] names = new String[in.readInt()];
          for (int j = 0; j < names.length; j++) {
            names[j] = in.readUTF();
          }
          helpers[i] = names;
        }
        return new HelpersIndex(helpers);
      } catch (Throwable e) {
        log.error("Problem reading {}", HELPERS_INDEX_NAME, e);
      }
    }
    return buildIndex(); // fallback to runtime generation when testing
  }

  public static HelpersIndex buildIndex() {
    IndexGenerator indexGenerator = new IndexGenerator();
    indexGenerator.buildIndex();
    return new HelpersIndex(indexGenerator.helpers.toArray(new String[0][]));
  }

  /**
   * Resolves each module's helpers from its build-time {@code $Muzzle}, falling back to the API.
   */
  static String[] resolveHelperClassNames(InstrumenterModule module) {
    String[] helperClassNames =
        InstrumenterModule.loadStaticMuzzleHelperClassNames(
            instrumenterClassLoader, module.getClass().getName());
    return null != helperClassNames ? helperClassNames : module.helperClassNames();
  }

  /** Generates the helpers index from known {@link InstrumenterModule}s on the build class-path. */
  static final class IndexGenerator {
    final List<String[]> helpers = new ArrayList<>();

    void buildIndex() {
      log.debug("Generating HelpersIndex");
      try {
        for (InstrumenterModule module : InstrumenterIndex.loadModules(instrumenterClassLoader)) {
          helpers.add(resolveHelperClassNames(module));
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Problem generating HelpersIndex", e);
      }
    }

    void writeIndex(Path indexFile) throws IOException {
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexFile)))) {
        out.writeInt(helpers.size());
        for (String[] names : helpers) {
          out.writeInt(names.length);
          for (String name : names) {
            out.writeUTF(name);
          }
        }
      }
    }

    /**
     * Called from the 'generateHelpersIndex' task in 'dd-java-agent/instrumentation/build.gradle'.
     */
    public static void main(String[] args) throws IOException {
      if (args.length < 1) {
        throw new IllegalArgumentException("Expected: index-dir");
      }
      Path indexDir = Paths.get(args[0]).toAbsolutePath();

      // satisfy some instrumenters that cache matchers in initializers
      HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
      SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());

      IndexGenerator indexGenerator = new IndexGenerator();
      indexGenerator.buildIndex();
      indexGenerator.writeIndex(indexDir.resolve(HELPERS_INDEX_NAME));
    }
  }
}
