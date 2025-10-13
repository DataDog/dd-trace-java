package datadog.trace.bootstrap;

import datadog.trace.util.ClassNameTrie;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.JarFile;
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
    return new AgentJarIndex(new String[0], ClassNameTrie.Builder.EMPTY_TRIE);
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
  static class IndexGenerator extends SimpleFileVisitor<Path> {
    private static final Set<String> ignoredFileNames =
        new HashSet<>(Arrays.asList("MANIFEST.MF", "NOTICE", "LICENSE.renamed"));

    private final Path resourcesDir;

    private final List<String> prefixes = new ArrayList<>();
    private final ClassNameTrie.Builder prefixTrie = new ClassNameTrie.Builder();

    private Path prefixRoot;
    private int prefixId;
    private Map<Integer, String> prefixMappings = new HashMap<>();

    IndexGenerator(Path resourcesDir) {
      this.resourcesDir = resourcesDir;

      prefixTrie.put("datadog.*", 0);
    }

    public void writeIndex(Path indexFile) throws IOException {
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexFile)))) {
        out.writeInt(prefixes.size());
        for (String p : prefixes) {
          out.writeUTF(p);
        }
        prefixTrie.writeTo(out);
      }
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      if (dir.getParent().equals(resourcesDir)) {
        prefixRoot = dir;
        prefixes.add(dir.getFileName() + "/");
        prefixId = prefixes.size();
        prefixMappings.put(prefixId, dir.getFileName().toString());
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      if (dir.equals(prefixRoot)) {
        prefixRoot = null;
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      if (null != prefixRoot) {
        String entryKey = computeEntryKey(prefixRoot.relativize(file));
        if (null != entryKey) {
          int existingPrefixId = prefixTrie.apply(entryKey);
          // warn if two subsections contain content under the same package prefix
          // because we're then unable to redirect requests to the right submodule
          // (ignore the two 'datadog.compiler' packages which allow duplication)
          if (existingPrefixId > 0 && prefixId != existingPrefixId) {
            log.warn(
                "Detected duplicate content '{}' under '{}', already seen in {}. Ensure your content is under a distinct directory.",
                entryKey,
                resourcesDir.relativize(file).getName(0), // prefix
                prefixMappings.get(existingPrefixId) // previous prefix
                );
          }
          prefixTrie.put(entryKey, prefixId);
          if (entryKey.endsWith("*")) {
            // optimization: wildcard will match everything under here so can skip
            return FileVisitResult.SKIP_SIBLINGS;
          }
        }
      }
      return FileVisitResult.CONTINUE;
    }

    private static String computeEntryKey(Path path) {
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
      Files.walkFileTree(resourcesDir, indexGenerator);
      indexGenerator.writeIndex(indexDir.resolve(AGENT_INDEX_FILE_NAME));
    }
  }
}
