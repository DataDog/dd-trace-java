package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.outline.AnnotationOutline.outlineAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.factory;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findDescriptor;
import static datadog.trace.agent.tooling.bytebuddy.outline.TypeFactory.findType;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;

public final class TypePoolFacade implements TypePool, SharedTypePools.Supplier {
  public static final TypePoolFacade INSTANCE = new TypePoolFacade();

  public static void registerAsSupplier() {
    SharedTypePools.registerIfAbsent(INSTANCE);
  }

  public static void switchContext(ClassLoader classLoader) {
    factory.get().switchContext(classLoader);
  }

  public static void beginTransform(String name, byte[] bytecode) {
    factory.get().beginTransform(name, bytecode);
  }

  public static void enableFullDescriptions() {
    factory.get().enableFullDescriptions();
  }

  @Override
  public TypePool typePool(ClassLoader classLoader) {
    return INSTANCE;
  }

  @Override
  public void annotationOfInterest(String name) {
    outlineAnnotation(name);
  }

  @Override
  public void endInstall() {
    factory.get().endInstall();
  }

  @Override
  public void endTransform() {
    factory.get().endTransform();
  }

  @Override
  public Resolution describe(String name) {
    TypeDescription type = name.charAt(0) == '[' ? findDescriptor(name) : findType(name);
    if (type instanceof TypeFactory.LazyType) {
      return new TypeFactory.LazyResolution((TypeFactory.LazyType) type);
    }
    return new Resolution.Simple(type);
  }

  @Override
  public void clear() {}
}
