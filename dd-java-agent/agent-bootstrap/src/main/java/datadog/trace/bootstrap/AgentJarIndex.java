package datadog.trace.bootstrap;

import datadog.instrument.utils.ClassNameTrie;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains an index to map class/resource names under nested jar prefixes (inst, metrics, etc.)
 */
public final class AgentJarIndex {
  private static final Logger log = LoggerFactory.getLogger(AgentJarIndex.class);

  private static final String AGENT_INDEX_FILE_NAME = "dd-java-agent.index";

  private final String[] prefixes;
  private final ClassNameTrie prefixTrie;

  private AgentJarIndex(String[] prefixes, ClassNameTrie prefixTrie) {
    this.prefixes = prefixes;
    this.prefixTrie = prefixTrie;
  }

  /** Returns the resolved entry name in the jar for the given resource. */
  public String resourceEntryName(String name) {
    int prefixId = prefixTrie.apply(name);
    if (prefixId == 0) {
      return name;
    } else if (prefixId > 0) {
      return prefixes[prefixId - 1] + (name.endsWith(".class") ? name + "data" : name);
    } else {
      return null;
    }
  }

  /** Returns the resolved entry name in the jar for the given class. */
  public String classEntryName(String name) {
    int prefixId = prefixTrie.apply(name);
    if (prefixId == 0) {
      return name.replace('.', '/') + ".class";
    } else if (prefixId > 0) {
      return prefixes[prefixId - 1] + name.replace('.', '/') + ".classdata";
    } else {
      return null;
    }
  }

  /** For testing purposes only. */
  public static AgentJarIndex emptyIndex() {
    return new AgentJarIndex(new String[0], ClassNameTrie.EMPTY_TRIE);
  }

  public static AgentJarIndex readIndex(JarFile agentJar) {
    try {
      ZipEntry indexEntry = agentJar.getEntry(AGENT_INDEX_FILE_NAME);
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(agentJar.getInputStream(indexEntry)))) {
        int prefixCount = in.readInt();
        String[] prefixes = new String[prefixCount];
        for (int i = 0; i < prefixCount; i++) {
          prefixes[i] = in.readUTF();
        }
        return new AgentJarIndex(prefixes, ClassNameTrie.readFrom(in));
      }
    } catch (Throwable e) {
      log.error("Unable to read {}", AGENT_INDEX_FILE_NAME, e);
      return null;
    }
  }

  /**
   * Generates an index from the contents of the 'build/resources' directory that makes up the agent
   * jar.
   */
  static class IndexGenerator {
    private static final Set<String> ignoredFileNames =
        new HashSet<>(Arrays.asList("MANIFEST.MF", "NOTICE", "LICENSE.renamed"));

    private final Path resourcesDir;

    private final List<String> prefixes = new ArrayList<>();
    private final ClassNameTrie.Builder prefixTrie = new ClassNameTrie.Builder();

    private final List<String> collectedEntryKeys = new ArrayList<>();
    private final List<Integer> collectedPrefixIds = new ArrayList<>();

    IndexGenerator(Path resourcesDir) {
      this.resourcesDir = resourcesDir;

      prefixTrie.put("datadog.*", 0);
    }

    void buildIndex() throws IOException {
      Set<String> seen = new HashSet<>();
      try (Stream<Path> paths = Files.walk(resourcesDir)) {
        paths
            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            .map(resourcesDir::relativize)
            .sorted()
            .filter(entry -> entry.getNameCount() >= 2)
            .forEach(
                entry -> {
                  String prefix = entry.getName(0) + "/";
                  int prefixId = prefixIdFor(prefix);
                  String entryKey = computeEntryKey(entry.subpath(1, entry.getNameCount()));
                  if (null != entryKey && seen.add(prefixId + "\0" + entryKey)) {
                    collectedEntryKeys.add(entryKey);
                    collectedPrefixIds.add(prefixId);
                  }
                });
      }

      for (int i = 0; i < collectedEntryKeys.size(); i++) {
        prefixTrie.put(collectedEntryKeys.get(i), collectedPrefixIds.get(i));
      }

      // warn if two subsections contain content under the same package prefix
      // because we're then unable to redirect requests to the right submodule
      for (int i = 0; i < collectedEntryKeys.size(); i++) {
        String entryKey = collectedEntryKeys.get(i);
        int expectedPrefixId = collectedPrefixIds.get(i);
        int indexedPrefixId = prefixTrie.apply(entryKey);
        if (indexedPrefixId != expectedPrefixId) {
          log.warn(
              "Detected duplicate content '{}' under '{}', already seen in {}. Ensure your content is under a distinct directory.",
              entryKey,
              getPrefix(expectedPrefixId),
              getPrefix(indexedPrefixId));
        }
      }

      collectedEntryKeys.clear();
      collectedPrefixIds.clear();
    }

    void writeIndex(Path indexFile) throws IOException {
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexFile)))) {
        out.writeInt(prefixes.size());
        for (String p : prefixes) {
          out.writeUTF(p);
        }
        prefixTrie.writeTo(out);
      }
    }

    private String getPrefix(int prefixId) {
      return prefixes.get(prefixId - 1);
    }

    private int prefixIdFor(String prefix) {
      int prefixId = 1 + prefixes.indexOf(prefix);
      if (prefixId < 1) {
        prefixes.add(prefix);
        prefixId = prefixes.size();
      }
      return prefixId;
    }

    static String computeEntryKey(Path path) {
      if (ignoredFileNames.contains(path.getFileName().toString())) {
        return null;
      }
      String entryKey = path.toString();
      if (File.separatorChar != '/') {
        entryKey = entryKey.replace(File.separatorChar, '/');
      }
      if (entryKey.startsWith("datadog/trace/instrumentation/")) {
        return "datadog.trace.instrumentation.*";
      }
      // use number of elements in the path to decide how 'unique' this path is
      int nameCount = path.getNameCount();
      if (nameCount > 1) {
        if (entryKey.startsWith("META-INF")) { // don't count META-INF as a unique element
          nameCount--;
        }
        // paths with three or more elements, or nested paths containing '.classdata' files
        // are considered unique enough that we can use the directory name as a wildcard key
        if (nameCount > 2 || entryKey.endsWith(".classdata")) {
          entryKey = entryKey.substring(0, entryKey.lastIndexOf('/') + 1) + "*";
        }
      }
      return entryKey.replace('/', '.');
    }

    public static void main(String[] args) throws IOException {
      if (args.length < 1) {
        throw new IllegalArgumentException("Expected: resources-dir");
      }

      Path resourcesDir = Paths.get(args[0]).toAbsolutePath();
      Path indexDir = resourcesDir;
      if (args.length == 2) {
        indexDir = Paths.get(args[1]).toAbsolutePath();
      }
      IndexGenerator indexGenerator = new IndexGenerator(resourcesDir);
      indexGenerator.buildIndex();
      indexGenerator.writeIndex(indexDir.resolve(AGENT_INDEX_FILE_NAME));
    }
  }
}
