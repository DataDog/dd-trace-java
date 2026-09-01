package datadog.trace.agent.tooling.advice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, neutral result of scanning a module's method advice. */
public final class AdviceScanResult {
  public enum UsageKind {
    TYPE,
    FIELD,
    METHOD,
    HANDLE,
    INVOKEDYNAMIC
  }

  /** Source location of a bytecode use. */
  public static final class SourceLocation {
    private final String className;
    private final int line;

    SourceLocation(String className, int line) {
      this.className = className;
      this.line = line;
    }

    public String getClassName() {
      return className;
    }

    public int getLine() {
      return line;
    }
  }

  /** A method-handle target without an ASM dependency in the public model. */
  public static final class HandleUse {
    private final int tag;
    private final String owner;
    private final String name;
    private final String descriptor;
    private final boolean interfaceOwner;

    HandleUse(int tag, String owner, String name, String descriptor, boolean interfaceOwner) {
      this.tag = tag;
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
      this.interfaceOwner = interfaceOwner;
    }

    public int getTag() {
      return tag;
    }

    public String getOwner() {
      return owner;
    }

    public String getName() {
      return name;
    }

    public String getDescriptor() {
      return descriptor;
    }

    public boolean isInterfaceOwner() {
      return interfaceOwner;
    }
  }

  /** One ordered bytecode use made by a scanned class. */
  public static final class Usage {
    private final UsageKind kind;
    private final SourceLocation source;
    private final int opcode;
    private final String owner;
    private final String name;
    private final String descriptor;
    private final boolean interfaceOwner;
    private final boolean implementedInterface;
    private final List<HandleUse> handles;

    Usage(
        UsageKind kind,
        SourceLocation source,
        int opcode,
        String owner,
        String name,
        String descriptor,
        boolean interfaceOwner,
        boolean implementedInterface,
        List<HandleUse> handles) {
      this.kind = kind;
      this.source = source;
      this.opcode = opcode;
      this.owner = owner;
      this.name = name;
      this.descriptor = descriptor;
      this.interfaceOwner = interfaceOwner;
      this.implementedInterface = implementedInterface;
      this.handles = immutableCopy(handles);
    }

    public UsageKind getKind() {
      return kind;
    }

    public SourceLocation getSource() {
      return source;
    }

    public int getOpcode() {
      return opcode;
    }

    /** Binary name of the used type or member owner. */
    public String getOwner() {
      return owner;
    }

    public String getName() {
      return name;
    }

    public String getDescriptor() {
      return descriptor;
    }

    public boolean isInterfaceOwner() {
      return interfaceOwner;
    }

    public boolean isImplementedInterface() {
      return implementedInterface;
    }

    public List<HandleUse> getHandles() {
      return handles;
    }
  }

  /** One discovered class. External leaves have no scanned bytecode. */
  public static final class ClassInfo {
    private final String className;
    private final boolean scanned;
    private final List<Usage> usages;

    ClassInfo(String className, boolean scanned, List<Usage> usages) {
      this.className = className;
      this.scanned = scanned;
      this.usages = immutableCopy(usages);
    }

    public String getClassName() {
      return className;
    }

    public boolean isScanned() {
      return scanned;
    }

    public List<Usage> getUsages() {
      return usages;
    }
  }

  private final List<String> adviceRoots;
  private final Map<String, ClassInfo> classes;

  AdviceScanResult(Collection<String> adviceRoots, Map<String, ClassInfo> classes) {
    this.adviceRoots = immutableCopy(adviceRoots);
    this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
  }

  public List<String> getAdviceRoots() {
    return adviceRoots;
  }

  public Map<String, ClassInfo> getClasses() {
    return classes;
  }

  public ClassInfo getClassInfo(String className) {
    return classes.get(className);
  }

  private static <T> List<T> immutableCopy(Collection<T> values) {
    return Collections.unmodifiableList(new ArrayList<>(values));
  }
}
