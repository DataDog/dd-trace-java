package datadog.trace.agent.tooling;

import datadog.instrument.utils.ClassNameTrie;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains an index from known instrumented class names to transformation id(s). */
public final class KnownTypesIndex {
  private static final Logger log = LoggerFactory.getLogger(KnownTypesIndex.class);

  private static final String KNOWN_TYPES_INDEX_NAME = "known-types.index";

  // marks results that match multiple transformations
  private static final int MULTIPLE_ID_MARKER = 0x1000;

  // lookup table of multiple-id results
  private final int[][] multipleIdTable;

  private final ClassNameTrie knownTypesTrie;

  private KnownTypesIndex(int[][] multipleIdTable, ClassNameTrie knownTypesTrie) {
    this.multipleIdTable = multipleIdTable;
    this.knownTypesTrie = knownTypesTrie;
  }

  public void apply(String name, BitSet mask, BitSet transformationIds) {
    int result = knownTypesTrie.apply(name);
    if (result >= 0) {
      if ((result & MULTIPLE_ID_MARKER) != 0) {
        for (int id : multipleIdTable[result & ~MULTIPLE_ID_MARKER]) {
          if (mask.get(id)) {
            transformationIds.set(id);
          }
        }
      } else if (mask.get(result)) {
        transformationIds.set(result);
      }
    }
  }

  public static KnownTypesIndex readIndex() {
    ClassLoader instrumenterClassLoader = Instrumenter.class.getClassLoader();
    URL indexResource = instrumenterClassLoader.getResource(KNOWN_TYPES_INDEX_NAME);
    if (null != indexResource) {
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(indexResource.openStream()))) {
        int multipleIdCount = in.readInt();
        int[][] multipleIdTable = new int[multipleIdCount][];
        for (int i = 0; i < multipleIdCount; i++) {
          int idCount = in.readInt();
          int[] ids = new int[idCount];
          for (int j = 0; j < idCount; j++) {
            ids[j] = in.readInt();
          }
          multipleIdTable[i] = ids;
        }
        return new KnownTypesIndex(multipleIdTable, ClassNameTrie.readFrom(in));
      } catch (Throwable e) {
        log.error("Problem reading {}", KNOWN_TYPES_INDEX_NAME, e);
      }
    }
    return buildIndex(); // fallback to runtime generation when testing
  }

  public static KnownTypesIndex buildIndex() {
    IndexGenerator indexGenerator = new IndexGenerator();
    indexGenerator.buildIndex();
    // bypass writing to file, convert into structure expected at runtime
    int[][] multipleIdTable = new int[indexGenerator.multipleIdTable.size()][];
    for (int i = 0; i < multipleIdTable.length; i++) {
      multipleIdTable[i] = indexGenerator.multipleIdTable.get(i).stream().toArray();
    }
    return new KnownTypesIndex(multipleIdTable, indexGenerator.knownTypesTrie.buildTrie());
  }

  /** Generates an index from known instrumented types referenced by {@link Instrumenter}s. */
  static class IndexGenerator {
    final ClassNameTrie.Builder knownTypesTrie = new ClassNameTrie.Builder();
    final List<BitSet> multipleIdTable = new ArrayList<>();

    public void buildIndex() {
      log.debug("Generating KnownTypesIndex");
      InstrumenterIndex instrumenterIndex = InstrumenterIndex.readIndex();
      for (InstrumenterModule module : instrumenterIndex.modules()) {
        for (Instrumenter member : module.typeInstrumentations()) {
          int transformationId = instrumenterIndex.transformationId(member);
          if (member instanceof Instrumenter.ForSingleType) {
            String type = ((Instrumenter.ForSingleType) member).instrumentedType();
            indexKnownType(member, type, transformationId);
          } else if (member instanceof Instrumenter.ForKnownTypes) {
            for (String type : ((Instrumenter.ForKnownTypes) member).knownMatchingTypes()) {
              indexKnownType(member, type, transformationId);
            }
          }
        }
      }
    }

    /** Indexes a single match from known-type to transformation-id. */
    private void indexKnownType(Instrumenter instrumenter, String knownType, int transformationId) {
      if (null == knownType || knownType.isEmpty()) {
        throw new IllegalArgumentException(
            instrumenter.getClass() + " declares a null or empty known-type");
      }
      int existingId = knownTypesTrie.apply(knownType);
      if (existingId < 0) {
        knownTypesTrie.put(knownType, transformationId);
      } else {
        BitSet transformationIds;
        if ((existingId & MULTIPLE_ID_MARKER) != 0) {
          // add new transformation-id to existing table entry, no need to update trie
          transformationIds = multipleIdTable.get(existingId & ~MULTIPLE_ID_MARKER);
        } else {
          // create new table entry to hold multiple ids and update trie with its offset
          knownTypesTrie.put(knownType, multipleIdTable.size() | MULTIPLE_ID_MARKER);
          transformationIds = new BitSet();
          multipleIdTable.add(transformationIds);
          transformationIds.set(existingId);
        }
        transformationIds.set(transformationId);
      }
    }

    public void writeIndex(Path indexFile) throws IOException {
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexFile)))) {
        out.writeInt(multipleIdTable.size());
        for (BitSet ids : multipleIdTable) {
          out.writeInt(ids.cardinality());
          for (int id = ids.nextSetBit(0); id >= 0; id = ids.nextSetBit(id + 1)) {
            out.writeInt(id);
          }
        }
        knownTypesTrie.writeTo(out);
      }
    }

    /**
     * Called from 'generateKnownTypesIndex' task in 'dd-java-agent/instrumentation/build.gradle'.
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
      indexGenerator.writeIndex(indexDir.resolve(KNOWN_TYPES_INDEX_NAME));
    }
  }
}
