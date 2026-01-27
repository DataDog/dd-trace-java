package datadog.trace.agent.tooling;

import static datadog.trace.agent.tooling.InstrumenterModuleFilter.ALL_MODULES;
import static datadog.trace.agent.tooling.InstrumenterModuleFilter.forTargetSystemsOrExcludeProvider;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.disjoint;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains an index of known {@link InstrumenterModule}s and their expected transformations.
 *
 * <p>This index is not thread-safe; it expects only one thread to iterate over it at a time. It
 * also assumes indexed types have simple ASCII names which are less than 256 characters long. Also,
 * because it encodes enabled systems as a 2-byte bitset, it can support at most 16 distinct
 * TargetSystem values.
 */
final class InstrumenterIndex {
  private static final Logger log = LoggerFactory.getLogger(InstrumenterIndex.class);

  private static final String INSTRUMENTER_INDEX_NAME = "instrumenter.index";

  // Special memberCount that indicates a module contains itself as a transformation
  private static final int SELF_MEMBERSHIP = 0xFF;

  static final ClassLoader instrumenterClassLoader = Instrumenter.class.getClassLoader();

  private final int instrumentationCount;
  private final int transformationCount;

  private final InstrumenterModule[] modules;

  // packed sequence of module type names and their expected member names:
  // module1, targetSystems, flags, memberCount, memberA, memberB, (targetSystemOverrides), module2,
  // memberCount, memberC, ...
  // (each string is encoded as its length plus that number of ASCII bytes)
  private final byte[] packedNames;
  private int nameIndex;

  // current module details
  private int instrumentationId = -1;
  private String moduleName;
  private int memberCount;
  private boolean hasTargetSystemOverrides;

  // current member details
  private int transformationId = -1;
  private String memberName;
  private Map<String, Set<InstrumenterModule.TargetSystem>> memberAdviceTargetOverrides;

  private InstrumenterIndex(int instrumentationCount, int transformationCount, byte[] packedNames) {
    this.modules = new InstrumenterModule[instrumentationCount];
    this.instrumentationCount = instrumentationCount;
    this.transformationCount = transformationCount;
    this.packedNames = packedNames;
  }

  /**
   * Queries the index to select modules that are either eligible to the provided targetSystems
   * either are ExcludeFilterProvider
   *
   * @param targetSystems the enabled target systems
   * @return an iterable of modules that apply.
   */
  public Iterable<InstrumenterModule> modules(
      final Set<InstrumenterModule.TargetSystem> targetSystems) {
    return modules(forTargetSystemsOrExcludeProvider(targetSystems));
  }

  public Iterable<InstrumenterModule> modules() {
    return modules(ALL_MODULES);
  }

  public Iterable<InstrumenterModule> modules(final InstrumenterModuleFilter filter) {
    return () -> new ModuleIterator(filter);
  }

  final class ModuleIterator implements Iterator<InstrumenterModule> {
    private InstrumenterModule module;
    private final InstrumenterModuleFilter filter;

    ModuleIterator(final InstrumenterModuleFilter filter) {
      this.filter = filter;
      restart();
    }

    @Override
    public boolean hasNext() {
      while (null == module && hasNextModule()) {
        module = nextModule(filter);
      }
      return null != module;
    }

    @Override
    public InstrumenterModule next() {
      if (hasNext()) {
        InstrumenterModule result = module;
        module = null;
        return result;
      } else {
        throw new NoSuchElementException();
      }
    }
  }

  /** Maximum known count of {@link InstrumenterModule} instrumentations. */
  public int instrumentationCount() {
    return instrumentationCount;
  }

  /** Maximum known count of {@link Instrumenter} transformations. */
  public int transformationCount() {
    return transformationCount;
  }

  /** Returns the id allocated to the instrumentation; {@code -1} if unknown. */
  public int instrumentationId(InstrumenterModule module) {
    if (module.getClass().getName().equals(moduleName)) {
      return instrumentationId;
    }
    return -1;
  }

  /** Returns the id allocated to the transformation; {@code -1} if unknown. */
  public int transformationId(Instrumenter member) {
    if (null == memberName && memberCount > 0) {
      nextMember(); // move through expected members as transformations are applied
    }
    if (null != memberName && member.getClass().getName().endsWith(memberName)) {
      memberName = null; // mark member as used for this iteration
      return transformationId;
    }
    // reset back the overrides
    memberAdviceTargetOverrides = null;
    return -1;
  }

  public boolean isAdviceEnabled(
      String adviceClass, Set<InstrumenterModule.TargetSystem> enabledSystems) {
    if (memberAdviceTargetOverrides == null) {
      return true;
    }
    Set<InstrumenterModule.TargetSystem> targetSystemOverrides =
        memberAdviceTargetOverrides.get(adviceClass.substring(adviceClass.lastIndexOf('.') + 1));
    return null == targetSystemOverrides || !disjoint(targetSystemOverrides, enabledSystems);
  }

  /** Resets the iteration to the start of the index. */
  void restart() {
    nameIndex = 0;
    instrumentationId = -1;
    transformationId = -1;
    memberCount = 0;
    memberAdviceTargetOverrides = null;
    hasTargetSystemOverrides = false;
  }

  /** Is there another known {@link InstrumenterModule} left in the index? */
  boolean hasNextModule() {
    return instrumentationCount - instrumentationId > 1;
  }

  /** Returns the next {@link InstrumenterModule} in the index. */
  InstrumenterModule nextModule(InstrumenterModuleFilter filter) {
    while (memberCount > 0) {
      skipMember(); // skip past unmatched members from previous module
    }
    InstrumenterModule module = modules[++instrumentationId];
    if (null != module) {
      // use data from previously loaded module
      moduleName = module.getClass().getName();
      skipName();
    } else {
      moduleName = readName();
    }
    // global module target systems
    final short systems = readShort();
    final Set<InstrumenterModule.TargetSystem> moduleTargetSystems = decodeTargetSystems(systems);
    // flags
    final byte flags = (byte) readNumber();
    final boolean isExcludeProvider = decodeModuleIsExcludeProvider(flags);
    hasTargetSystemOverrides = decodeModuleHasTargetSystemOverrides(flags);
    memberAdviceTargetOverrides = null;
    memberCount = readNumber();
    if (SELF_MEMBERSHIP == memberCount) {
      transformationId++;
      memberName = moduleName;
      memberCount = 0;
      decodeTargetSystemOverrides();
    } else {
      memberName = null;
    }
    if (filter.test(moduleName, moduleTargetSystems, isExcludeProvider)) {
      if (module == null) {
        module = buildModule();
        modules[instrumentationId] = module;
      }
      return module;
    } else {
      log.debug("Skipping module {} as it is excluded", moduleName);
    }
    return null;
  }

  private InstrumenterModule buildModule() {
    try {
      @SuppressWarnings({"rawtypes", "unchecked"})
      Class<InstrumenterModule> nextType = (Class) instrumenterClassLoader.loadClass(moduleName);
      return nextType.getConstructor().newInstance();
    } catch (Throwable e) {
      log.error("Failed to build instrumentation module {}", moduleName, e);
      return null;
    }
  }

  /** Moves onto the next member in the expected sequence. */
  private void nextMember() {
    memberCount--;
    transformationId++;
    memberName = readName();
    decodeTargetSystemOverrides();
  }

  private void decodeTargetSystemOverrides() {
    if (hasTargetSystemOverrides) {
      int overrideCount = readNumber();
      if (overrideCount > 0) {
        memberAdviceTargetOverrides = new HashMap<>(overrideCount * 4 / 3, 0.75f);
        for (int i = 0; i < overrideCount; i++) {
          memberAdviceTargetOverrides.put(readName(), decodeTargetSystems(readShort()));
        }
      } else {
        memberAdviceTargetOverrides = null;
      }
    }
  }

  /** Skips past the next member in the expected sequence. */
  private void skipMember() {
    memberCount--;
    transformationId++;
    skipName();
    if (hasTargetSystemOverrides) {
      // skip next N custom overrides
      for (int i = 0, overrideCount = readNumber(); i < overrideCount; i++) {
        skipName();
        // skip the target system short
        this.nameIndex += 2;
      }
    }
  }

  /** Reads a single-byte-encoded string from the packed name sequence. */
  private String readName() {
    int length = readNumber();
    String name = new String(packedNames, nameIndex, length, ISO_8859_1);
    nameIndex += length;
    return name;
  }

  /** Skips a single-byte-encoded string from the packed name sequence. */
  private void skipName() {
    int length = readNumber();
    nameIndex += length;
  }

  /** Reads an unsigned byte from the packed name sequence. */
  private int readNumber() {
    return 0xFF & (int) packedNames[nameIndex++];
  }

  private short readShort() {
    return (short) ((packedNames[nameIndex++] << 8) + (packedNames[nameIndex++] & 0xFF));
  }

  public static InstrumenterIndex readIndex() {
    URL indexResource = instrumenterClassLoader.getResource(INSTRUMENTER_INDEX_NAME);
    if (null != indexResource) {
      try (DataInputStream in =
          new DataInputStream(new BufferedInputStream(indexResource.openStream()))) {
        int instrumentationCount = in.readInt();
        int transformationCount = in.readInt();
        int packedNamesLength = in.readInt();
        byte[] packedNames = new byte[packedNamesLength];
        in.readFully(packedNames);
        return new InstrumenterIndex(instrumentationCount, transformationCount, packedNames);
      } catch (Throwable e) {
        log.error("Problem reading {}", INSTRUMENTER_INDEX_NAME, e);
      }
    }
    return buildIndex(); // fallback to runtime generation when testing
  }

  public static InstrumenterIndex buildIndex() {
    IndexGenerator indexGenerator = new IndexGenerator();
    indexGenerator.buildIndex();
    // bypass writing to file, convert into structure expected at runtime
    return new InstrumenterIndex(
        indexGenerator.instrumentationCount,
        indexGenerator.transformationCount,
        indexGenerator.packedNames.toByteArray());
  }

  /** Loads instrumentation modules annotated with {@code @AutoService}. */
  static List<InstrumenterModule> loadModules(ClassLoader loader) throws IOException {
    List<InstrumenterModule> modules = new ArrayList<>();
    for (String moduleName : loadModuleNames(loader)) {
      try {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Class<InstrumenterModule> moduleType = (Class) loader.loadClass(moduleName);
        modules.add(moduleType.getConstructor().newInstance());
      } catch (Throwable e) {
        log.error("Failed to load instrumentation module {}", moduleName, e);
      }
    }
    // enforce module ordering (lowest-value first) before indexing
    modules.sort(Comparator.comparingInt(InstrumenterModule::order));
    return modules;
  }

  /** Loads the type names of instrumentation modules annotated with {@code @AutoService}. */
  private static String[] loadModuleNames(ClassLoader loader) throws IOException {
    Set<String> lines = new LinkedHashSet<>();
    Enumeration<URL> urls =
        loader.getResources("META-INF/services/datadog.trace.agent.tooling.InstrumenterModule");
    while (urls.hasMoreElements()) {
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(urls.nextElement().openStream(), UTF_8))) {
        String line = reader.readLine();
        while (line != null) {
          lines.add(line);
          line = reader.readLine();
        }
      }
    }
    return lines.toArray(new String[0]);
  }

  static byte encodeModuleFlags(final InstrumenterModule module, final boolean hasCustomOverrides) {
    byte ret = (byte) (hasCustomOverrides ? 1 : 0);
    if (module instanceof ExcludeFilterProvider) {
      ret |= (byte) 0x2;
    }
    return ret;
  }

  static boolean decodeModuleIsExcludeProvider(byte flags) {
    return (flags & 2) != 0;
  }

  static boolean decodeModuleHasTargetSystemOverrides(byte flags) {
    return (flags & 1) != 0;
  }

  static short encodeTargetSystems(Set<InstrumenterModule.TargetSystem> targetSystems) {
    short ret = 0;
    for (InstrumenterModule.TargetSystem ts : targetSystems) {
      ret |= (short) (1 << ts.ordinal());
    }
    return ret;
  }

  static Set<InstrumenterModule.TargetSystem> decodeTargetSystems(final short targetSystems) {
    final Set<InstrumenterModule.TargetSystem> ret =
        EnumSet.noneOf(InstrumenterModule.TargetSystem.class);
    short i = 1;
    for (InstrumenterModule.TargetSystem targetSystem : InstrumenterModule.TargetSystem.values()) {
      if ((targetSystems & i) != 0) {
        ret.add(targetSystem);
      }
      i <<= 1;
    }
    return ret;
  }

  static Set<InstrumenterModule.TargetSystem> findModuleTargetSystems(
      final InstrumenterModule module) {
    final Set<InstrumenterModule.TargetSystem> ret =
        EnumSet.noneOf(InstrumenterModule.TargetSystem.class);
    for (InstrumenterModule.TargetSystem targetSystem : InstrumenterModule.TargetSystem.values()) {
      if (module.isApplicable(singleton(targetSystem))) {
        ret.add(targetSystem);
      }
    }
    return ret;
  }

  static void writeAdviceOverrides(
      final DataOutputStream out,
      Instrumenter instrumenter,
      Map<Instrumenter, Map<String, Set<InstrumenterModule.TargetSystem>>> allModuleOverrides)
      throws IOException {
    if (allModuleOverrides.isEmpty()) {
      return;
    }
    final Map<String, Set<InstrumenterModule.TargetSystem>> overrides =
        allModuleOverrides.get(instrumenter);
    if (overrides == null) {
      out.writeByte(0);
      return;
    }
    out.writeByte(overrides.size());
    for (Map.Entry<String, Set<InstrumenterModule.TargetSystem>> entry : overrides.entrySet()) {
      // pack target system and advice name
      out.writeByte(entry.getKey().length());
      out.writeBytes(entry.getKey());
      out.writeShort(encodeTargetSystems(entry.getValue()));
    }
  }

  /** Generates an index from known {@link InstrumenterModule}s on the build class-path. */
  static final class IndexGenerator {
    final ByteArrayOutputStream packedNames = new ByteArrayOutputStream();

    int instrumentationCount = 0;
    int transformationCount = 0;

    public void buildIndex() {
      log.debug("Generating InstrumenterIndex");
      try (DataOutputStream out = new DataOutputStream(packedNames)) {
        for (InstrumenterModule module : loadModules(instrumenterClassLoader)) {
          String moduleName = module.getClass().getName();
          instrumentationCount++;
          // scan the advices to find overrides
          Map<Instrumenter, Map<String, Set<InstrumenterModule.TargetSystem>>> adviceOverrides =
              new HashMap<>();
          final Set<InstrumenterModule.TargetSystem> moduleTargetSystems =
              findModuleTargetSystems(module);
          for (Instrumenter instrumenter : module.typeInstrumentations()) {
            final Map<String, Set<InstrumenterModule.TargetSystem>> overrides =
                AdviceAppliesOnScanner.extractTargetSystemOverrides(instrumenter);
            overrides.forEach((ignored, systems) -> moduleTargetSystems.addAll(systems));
            if (!overrides.isEmpty()) {
              adviceOverrides.put(instrumenter, overrides);
            }
          }
          // merge all the overrides to have the largest condition for this module
          out.writeByte(moduleName.length());
          out.writeBytes(moduleName);
          // write the global target systems for the module
          out.writeShort(encodeTargetSystems(moduleTargetSystems));
          // write flags
          out.writeByte(encodeModuleFlags(module, !adviceOverrides.isEmpty()));
          try {
            List<Instrumenter> members = module.typeInstrumentations();
            if (members.equals(singletonList(module))) {
              transformationCount++;
              out.writeByte(SELF_MEMBERSHIP);
              writeAdviceOverrides(out, module, adviceOverrides);

            } else {
              out.writeByte(members.size());
              for (Instrumenter member : members) {
                // we only need the simple name for matching purposes
                String memberName = member.getClass().getSimpleName();
                transformationCount++;
                out.writeByte(memberName.length());
                out.writeBytes(memberName);
                writeAdviceOverrides(out, member, adviceOverrides);
              }
            }
          } catch (Throwable e) {
            log.error("Failed to index instrumentation module {}", moduleName, e);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("Problem generating InstrumenterIndex", e);
      }
    }

    public void writeIndex(Path indexFile) throws IOException {
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexFile)))) {
        out.writeInt(instrumentationCount);
        out.writeInt(transformationCount);
        out.writeInt(packedNames.size());
        out.write(packedNames.toByteArray());
      }
    }

    /**
     * Called from 'generateInstrumenterIndex' task in 'dd-java-agent/instrumentation/build.gradle'.
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
      indexGenerator.writeIndex(indexDir.resolve(INSTRUMENTER_INDEX_NAME));
    }
  }
}
