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
    // describe elements as deferred types, lazily evaluated using the current context
    TypeDescription type = name.charAt(0) == '[' ? findDescriptor(name) : findType(name);
    if (type instanceof TypeFactory.LazyType) {
      return new TypeFactory.LazyResolution((TypeFactory.LazyType) type);
    }
    return new Resolution.Simple(type);
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
}
