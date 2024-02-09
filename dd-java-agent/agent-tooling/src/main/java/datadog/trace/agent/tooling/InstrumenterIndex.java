package datadog.trace.agent.tooling;

import static java.nio.charset.StandardCharsets.US_ASCII;

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
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maintains an index of known {@link Instrumenter} types sorted by {@link InstrumenterModule}. */
final class InstrumenterIndex {
  private static final Logger log = LoggerFactory.getLogger(InstrumenterIndex.class);

  private static final String INSTRUMENTER_INDEX_NAME = "instrumenter.index";

  private final int moduleCount;
  private final int instrumenterCount;

  // packed sequence of module types and their expected instrumenter types:
  // module1, memberCount, memberA, memberB, module2, memberCount, memberC, ...
  // (each string is encoded as its length plus that number of ASCII bytes)
  private final byte[] packedNames;

  private int nameIndex;

  private int moduleIndex;
  private int instrumenterIndex;

  // current module details
  private String moduleName;
  private int memberCount;
  private int memberIndex;

  private InstrumenterIndex(int moduleCount, int instrumenterCount, byte[] packedNames) {
    this.moduleCount = moduleCount;
    this.instrumenterCount = instrumenterCount;
    this.packedNames = packedNames;
  }

  /** Total number of modules in this index. */
  public int getModuleCount() {
    return moduleCount;
  }

  /** Total number of instrumenters in this index. */
  public int getInstrumenterCount() {
    return instrumenterCount;
  }

  /** Moves to the next indexed module. */
  public String nextModule() {
    if (moduleIndex >= moduleCount) {
      throw new NoSuchElementException();
    }

    // skip names expected by previous module
    while (memberIndex++ < memberCount) {
      instrumenterIndex++;
      skipName();
    }

    // setup next module
    moduleName = readName();
    moduleIndex++;
    memberCount = readNumber();
    memberIndex = 0;

    return moduleName;
  }

  /** The id pre-allocated to the instrumenter; {@code -1} if it's not indexed. */
  public int instrumenterId(String instrumenterName) {
    if (memberIndex++ < memberCount) {
      int id = instrumenterIndex++;
      if (instrumenterName.equals(readName())) {
        return id; // only use when the observed name matches the expected name
      }
    }
    return -1;
  }

  /** Reads an unsigned byte from the packed name sequence. */
  private int readNumber() {
    return 0xFF & (int) packedNames[nameIndex++];
  }

  /** Reads an ASCII string from the packed name sequence. */
  private String readName() {
    int length = readNumber();
    if (length > 0) {
      String name = new String(packedNames, nameIndex, length, US_ASCII);
      nameIndex += length;
      return name;
    } else {
      return moduleName; // empty string means this is a self-referencing module
    }
  }

  /** Skips an ASCII string in the packed name sequence. */
  private void skipName() {
    nameIndex += readNumber();
  }

  public static InstrumenterIndex readIndex() {
    ClassLoader instrumenterClassLoader = Instrumenter.class.getClassLoader();
    URL indexResource = instrumenterClassLoader.getResource(INSTRUMENTER_INDEX_NAME);
    if (null != indexResource) {
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(indexResource.openStream()))) {
        int moduleCount = in.readInt();
        int instrumenterCount = in.readInt();
        int packedNamesLength = in.readInt();
        byte[] packedNames = new byte[packedNamesLength];
        in.readFully(packedNames);
        return new InstrumenterIndex(moduleCount, instrumenterCount, packedNames);
      } catch (Throwable e) {
        log.error("Problem reading {}", INSTRUMENTER_INDEX_NAME, e);
      }
    }
    return null;
  }

  /** Generates an index of known {@link Instrumenter}s in each {@link InstrumenterModule}. */
  static class IndexGenerator {

    private final List<String> moduleNames = new ArrayList<>();
    private final List<List<String>> instrumenterNames = new ArrayList<>();

    private int moduleCount = 0;
    private int instrumenterCount = 0;
    private int packedNamesLength = 0;

    public void buildIndex() {
      log.debug("Generating InstrumenterIndex");
      for (InstrumenterModule module :
          InstrumenterModules.load(Instrumenter.class.getClassLoader())) {
        moduleCount++;
        String moduleName = module.getClass().getName();
        packedNamesLength += 1 + moduleName.length() + 1; // includes byte for memberCount
        moduleNames.add(moduleName);
        List<String> memberNames = new ArrayList<>();
        for (Instrumenter member : module.typeInstrumentations()) {
          instrumenterCount++;
          // self-referencing modules use empty string to represent themselves as a member
          String memberName = module == member ? "" : member.getClass().getName();
          packedNamesLength += 1 + memberName.length();
          memberNames.add(memberName);
        }
        instrumenterNames.add(memberNames);
      }
    }

    public void writeIndex(Path indexFile) throws IOException {
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexFile)))) {
        out.writeInt(moduleCount);
        out.writeInt(instrumenterCount);
        out.writeInt(packedNamesLength);
        for (int i = 0; i < moduleCount; i++) {
          String moduleName = moduleNames.get(i);
          List<String> memberNames = instrumenterNames.get(i);
          out.writeByte(moduleName.length());
          out.writeBytes(moduleName);
          out.writeByte(memberNames.size());
          for (String memberName : memberNames) {
            out.writeByte(memberName.length());
            out.writeBytes(memberName);
          }
        }
      }
    }

    /**
     * Called from 'generateInstrumenterIndex' task in 'dd-java-agent/instrumentation/build.gradle'.
     */
    public static void main(String[] args) throws IOException {
      if (args.length < 1) {
        throw new IllegalArgumentException("Expected: resources-dir");
      }

      Path resourcesDir = Paths.get(args[0]).toAbsolutePath();

      // satisfy some instrumenters that cache matchers in initializers
      HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
      SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());

      IndexGenerator indexGenerator = new IndexGenerator();
      indexGenerator.buildIndex();
      indexGenerator.writeIndex(resourcesDir.resolve(INSTRUMENTER_INDEX_NAME));
    }
  }
}
