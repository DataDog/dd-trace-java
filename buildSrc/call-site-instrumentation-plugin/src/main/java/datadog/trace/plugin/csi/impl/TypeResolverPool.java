package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;
import static datadog.trace.plugin.csi.util.CallSiteUtils.repeat;

import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.model.resolution.SymbolReference;
import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionFactory;
import datadog.trace.plugin.csi.HasErrors.Failure;
import datadog.trace.plugin.csi.TypeResolver;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

public class TypeResolverPool implements TypeResolver {

  private final List<ClassLoader> classpath;
  private final Map<Type, Class<?>> resolvedTypes = new HashMap<>();
  private final Map<MethodType, Executable> resolvedMethods = new HashMap<>();

  public TypeResolverPool() {
    this(Thread.currentThread().getContextClassLoader());
  }

  public TypeResolverPool(@Nonnull final ClassLoader... classpath) {
    this.classpath = Arrays.asList(classpath);
    resolvedTypes.put(Type.BYTE_TYPE, byte.class);
    resolvedTypes.put(Type.CHAR_TYPE, char.class);
    resolvedTypes.put(Type.DOUBLE_TYPE, double.class);
    resolvedTypes.put(Type.FLOAT_TYPE, float.class);
    resolvedTypes.put(Type.INT_TYPE, int.class);
    resolvedTypes.put(Type.LONG_TYPE, long.class);
    resolvedTypes.put(Type.SHORT_TYPE, short.class);
    resolvedTypes.put(Type.BOOLEAN_TYPE, boolean.class);
    resolvedTypes.put(Type.VOID_TYPE, void.class);
  }

  @Override
  @Nonnull
  public Class<?> resolveType(@Nonnull final Type type) {
    return resolvedTypes.computeIfAbsent(type, this::resolveNewType);
  }

  private Class<?> resolveNewType(@Nonnull final Type type) {
    if (type.getSort() == Type.METHOD) {
      throw new IllegalArgumentException(type + " is a method");
    }
    Throwable cause = null;
    final String className = getResolvableClassName(type);
    for (ClassLoader classLoader : classpath) {
      try {
        return Class.forName(className, true, classLoader);
      } catch (Throwable t) {
        cause = t;
      }
    }
    if (cause == null) {
      throw new ResolutionException(new Failure(ErrorCode.UNRESOLVED_TYPE, type));
    } else {
      throw new ResolutionException(new Failure(cause, ErrorCode.UNRESOLVED_TYPE, type));
    }
  }

  private String getResolvableClassName(final Type type) {
    switch (type.getSort()) {
      case Type.ARRAY:
        Type element = type.getElementType();
        String elementClassName =
            element.getSort() == Type.OBJECT
                ? "L" + element.getClassName() + ";"
                : element.getInternalName();
        return repeat('[', type.getDimensions()) + elementClassName;
      case Type.OBJECT:
        return type.getClassName();
      default:
        throw new IllegalArgumentException(
            "Primitive types should have already been resolved " + type);
    }
  }

  @Override
  @Nonnull
  public Executable resolveMethod(@Nonnull final MethodType method) {
    return resolvedMethods.computeIfAbsent(method, this::resolveNewMethod);
  }

  private Executable resolveNewMethod(@Nonnull final MethodType method) {
    final Class<?> owner = resolveType(method.getOwner());
    final Type[] argumentTypes = method.getMethodType().getArgumentTypes();
    final Class<?>[] arguments = new Class<?>[argumentTypes.length];
    for (int i = 0; i < argumentTypes.length; i++) {
      arguments[i] = resolveType(argumentTypes[i]);
    }
    try {
      if (method.isConstructor()) {
        try {
          return owner.getDeclaredConstructor(arguments);
        } catch (final NoSuchMethodException e) {
          return owner.getConstructor(arguments);
        }
      } else {
        try {
          return owner.getDeclaredMethod(method.getMethodName(), arguments);
        } catch (final NoSuchMethodException e) {
          return owner.getMethod(method.getMethodName(), arguments);
        }
      }
    } catch (final ResolutionException e) {
      throw e;
    } catch (final Throwable e) {
      throw new ResolutionException(new Failure(e, ErrorCode.UNRESOLVED_METHOD, method));
    }
  }

  @Override
  public TypeSolver getParent() {
    return null;
  }

  @Override
  public void setParent(final TypeSolver parent) {}

  @Override
  public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(final String name) {
    try {
      // inner classes use a dollar instead of a dot in the class name
      final StringBuilder builder = new StringBuilder(name.length());
      final String[] parts = name.split("\\.");
      for (int i = 0; i < parts.length; i++) {
        final String part = parts[i];
        if (i > 0) {
          final String prev = parts[i - 1];
          final boolean inner =
              Character.isUpperCase(prev.charAt(0)) && Character.isUpperCase(part.charAt(0));
          builder.append(inner ? '$' : '.');
        }
        builder.append(part);
      }
      final Type type = classNameToType(builder.toString());
      final Class<?> clazz = resolveType(type);
      return SymbolReference.solved(ReflectionFactory.typeDeclarationFor(clazz, getRoot()));
    } catch (final Throwable e) {
      return SymbolReference.unsolved(ResolvedReferenceTypeDeclaration.class);
    }
  }
}
