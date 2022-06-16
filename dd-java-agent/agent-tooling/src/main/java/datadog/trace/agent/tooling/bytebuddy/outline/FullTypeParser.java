package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findDescriptor;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;
import static net.bytebuddy.jar.asm.ClassReader.SKIP_CODE;
import static net.bytebuddy.jar.asm.ClassReader.SKIP_DEBUG;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/** Provides access to byte-buddy's full type parser, so we can use it with our outline approach. */
final class FullTypeParser extends TypePool.Default implements TypeParser {

  FullTypeParser() {
    super(CacheProvider.NoOp.INSTANCE, ClassFileLocator.NoOp.INSTANCE, ReaderMode.FAST);
  }

  @Override
  public TypeDescription parse(byte[] bytecode) {
    ClassReader classReader = OpenedClassReader.of(bytecode);
    CustomTypeExtractor typeExtractor = new CustomTypeExtractor();
    classReader.accept(typeExtractor, SKIP_CODE | SKIP_DEBUG);
    return typeExtractor.toTypeDescription();
  }

  @Override
  public TypeDescription parse(Class<?> loadedType) {
    return TypeDescription.ForLoadedType.of(loadedType);
  }

  @Override
  public Resolution describe(String name) {
    return new LazyResolution(name.charAt(0) == '[' ? findDescriptor(name) : findType(name));
  }

  @Override
  public void clear() {}

  /** Subclass to make 'toTypeDescription' visible. */
  final class CustomTypeExtractor extends TypeExtractor {
    @Override
    public TypeDescription toTypeDescription() {
      return super.toTypeDescription();
    }
  }

  static final class LazyResolution implements Resolution {
    private final TypeDescription type;
    private Boolean resolved;

    LazyResolution(TypeDescription type) {
      this.type = type;
    }

    @Override
    public boolean isResolved() {
      if (null == resolved) {
        try {
          type.getModifiers();
          resolved = true;
        } catch (Throwable e) {
          resolved = false;
        }
      }
      return resolved;
    }

    @Override
    public TypeDescription resolve() {
      return type;
    }
  }
}
