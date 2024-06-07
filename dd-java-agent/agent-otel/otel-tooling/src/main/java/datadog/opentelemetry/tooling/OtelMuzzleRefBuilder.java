package datadog.opentelemetry.tooling;

import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_INTERFACE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_FINAL;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_INTERFACE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_PRIVATE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_PUBLIC;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_PUBLIC_OR_PROTECTED;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_STATIC;

import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.Collection;
import net.bytebuddy.jar.asm.Type;

/** Maps OpenTelemetry muzzle references to the Datadog equivalent. */
public final class OtelMuzzleRefBuilder {
  private final Reference.Builder builder;

  OtelMuzzleRefBuilder(String className) {
    this.builder = new Reference.Builder(className);
  }

  public OtelMuzzleRefBuilder setSuperClassName(String superName) {
    builder.withSuperName(superName);
    return this;
  }

  public OtelMuzzleRefBuilder addInterfaceNames(Collection<String> interfaceNames) {
    for (String interfaceName : interfaceNames) {
      builder.withInterface(interfaceName);
    }
    return this;
  }

  public OtelMuzzleRefBuilder addInterfaceName(String interfaceName) {
    builder.withInterface(interfaceName);
    return this;
  }

  public OtelMuzzleRefBuilder addSource(String sourceName) {
    builder.withSource(sourceName, 0);
    return this;
  }

  public OtelMuzzleRefBuilder addSource(String sourceName, int line) {
    builder.withSource(sourceName, line);
    return this;
  }

  public OtelMuzzleRefBuilder addFlag(Flag flag) {
    builder.withFlag(flag.bit);
    return this;
  }

  public OtelMuzzleRefBuilder addField(
      Source[] sources, Flag[] flags, String fieldName, Type fieldType, boolean isDeclared) {
    builder.withField(Source.flatten(sources), Flag.flatten(flags), fieldName, fieldType);
    return this;
  }

  public OtelMuzzleRefBuilder addMethod(
      Source[] sources, Flag[] flags, String methodName, Type returnType, Type... argumentTypes) {
    builder.withMethod(
        Source.flatten(sources), Flag.flatten(flags), methodName, returnType, argumentTypes);
    return this;
  }

  public ClassRef build() {
    return new ClassRef(builder.build());
  }

  public static final class ClassRef extends Reference {
    public ClassRef(Reference ref) {
      super(
          ref.sources,
          ref.flags,
          ref.className,
          ref.superName,
          ref.interfaces,
          ref.fields,
          ref.methods);
    }

    public static OtelMuzzleRefBuilder builder(String className) {
      return new OtelMuzzleRefBuilder(className);
    }
  }

  public enum Flag {
    PUBLIC(EXPECTS_PUBLIC),
    PROTECTED_OR_HIGHER(EXPECTS_PUBLIC_OR_PROTECTED),
    PROTECTED(EXPECTS_PUBLIC_OR_PROTECTED),
    PACKAGE_OR_HIGHER(EXPECTS_NON_PRIVATE),
    PACKAGE(EXPECTS_NON_PRIVATE),
    PRIVATE_OR_HIGHER(0),
    PRIVATE(0),
    ABSTRACT(0),
    FINAL(0),
    NON_FINAL(EXPECTS_NON_FINAL),
    INTERFACE(EXPECTS_INTERFACE),
    NON_INTERFACE(EXPECTS_NON_INTERFACE),
    STATIC(EXPECTS_STATIC),
    NON_STATIC(EXPECTS_NON_STATIC);

    final int bit;

    Flag(int bit) {
      this.bit = bit;
    }

    public static int flatten(Flag[] flags) {
      int bits = 0;
      for (Flag flag : flags) {
        bits |= flag.bit;
      }
      return bits;
    }
  }

  public static final class Source {
    final String name;
    final int line;

    public Source(String name, int line) {
      this.name = name;
      this.line = line;
    }

    public static String[] flatten(Source[] sources) {
      String[] locations = new String[sources.length];
      for (int i = 0; i < sources.length; i++) {
        locations[i] = sources[i].name + ":" + sources[i].line;
      }
      return locations;
    }
  }
}
