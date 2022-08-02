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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentJarIndex {
  private static final Logger log = LoggerFactory.getLogger(AgentJarIndex.class);

  private static final String AGENT_INDEX_FILE_NAME = "dd-java-agent.index";

  private final String[] prefixes;
  private final ClassNameTrie prefixTrie;

  private AgentJarIndex(String[] prefixes, ClassNameTrie prefixTrie) {
    this.prefixes = prefixes;
    this.prefixTrie = prefixTrie;
  }

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
        ClassNameTrie.Builder prefixTrie = new ClassNameTrie.Builder();
        prefixTrie.readFrom(in);
        return new AgentJarIndex(prefixes, prefixTrie.buildTrie());
      }
    } catch (Throwable e) {
      log.error("Unable to read " + AGENT_INDEX_FILE_NAME, e);
      return null;
    }
  }

  static class IndexGenerator extends SimpleFileVisitor<Path> {
    private static final Set<String> ignoredFileNames =
        new HashSet<>(Arrays.asList("MANIFEST.MF", "NOTICE", "LICENSE.renamed"));

    private final Path resourcesDir;

    private final List<String> prefixes = new ArrayList<>();
    private final ClassNameTrie.Builder prefixTrie = new ClassNameTrie.Builder();

    private Path prefixRoot;
    private int prefixId;

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
          prefixTrie.put(entryKey, prefixId);
          if (entryKey.endsWith("*")) {
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
      int nameCount = path.getNameCount();
      if (nameCount > 1) {
        if (entryKey.startsWith("META-INF")) {
          nameCount--;
        }
        if (nameCount > 2 || entryKey.endsWith(".classdata")) {
          entryKey = entryKey.substring(0, entryKey.lastIndexOf('/') + 1) + "*";
        }
      }
      return entryKey.replace('/', '.');
    }

    public static void main(String[] args) throws IOException {
      Path resourcesDir = Paths.get(args[0]);
      IndexGenerator indexGenerator = new IndexGenerator(resourcesDir);
      Files.walkFileTree(resourcesDir, indexGenerator);
      indexGenerator.writeIndex(resourcesDir.resolve(AGENT_INDEX_FILE_NAME));
    }
  }
}
