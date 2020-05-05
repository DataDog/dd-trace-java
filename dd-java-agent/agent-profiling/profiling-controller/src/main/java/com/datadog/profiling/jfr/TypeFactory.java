package com.datadog.profiling.jfr;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@linkplain JFRType} factory class. The factory makes sure that custom types are properly set
 * up with access to {@linkplain ConstantPools} and {@linkplain Types} support classes and that the
 * types get correctly assigned a unique ID.
 */
final class TypeFactory {
  private final AtomicLong typeCounter = new AtomicLong(1);
  private final ConstantPools constantPools;
  private final Types types;

  TypeFactory(ConstantPools constantPools, Types types) {
    this.constantPools = constantPools;
    this.types = types;
  }

  /**
   * Create a new built-in type of the given name
   *
   * @param name the type name
   * @return new built-in type
   * @throws IllegalArgumentException if a built-in with the same name is already registered
   */
  BaseJFRType createBuiltinType(String name) {
    if (!Types.Builtin.hasType(name)) {
      throw new IllegalArgumentException();
    }
    Types.Builtin type = Types.Builtin.ofName(name);
    return new BuiltinJFRType(
        typeCounter.getAndIncrement(),
        type,
        type == Types.Builtin.STRING ? constantPools : null,
        types);
  }

  /**
   * Create a new custom type of the given name and structure
   *
   * @param name the type name
   * @param supertype the super type name - may be {@literal null}
   * @param structure the type structure - fields, annotations
   * @return new custom type
   * @throws IllegalArgumentException if the name belongs to one of the built-in types
   */
  BaseJFRType createCustomType(String name, String supertype, TypeStructure structure) {
    if (Types.Builtin.hasType(name)) {
      throw new IllegalArgumentException();
    }
    return new CustomJFRType(
        typeCounter.getAndIncrement(),
        name,
        supertype,
        structure,
        // TODO hack for event types not to go to constant pool
        !"jdk.jfr.Event".equals(supertype) ? constantPools : null,
        types);
  }
}
