package com.datadog.profiling.jfr;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class TypeFactory {
  private final AtomicLong typeCounter = new AtomicLong(1);
  private final ConstantPools constantPools;
  private final Types types;

  TypeFactory(ConstantPools constantPools, Types types) {
    this.constantPools = constantPools;
    this.types = types;
  }

  BaseType createBuiltinType(String name) {
    if (!Types.Builtin.hasType(name)) {
      throw new IllegalArgumentException();
    }
    Types.Builtin type = Types.Builtin.ofName(name);
    return new BuiltinType(
        typeCounter.getAndIncrement(),
        type,
        type == Types.Builtin.STRING ? constantPools : null,
        types);
  }

  BaseType createCustomType(String name, List<TypedField> fields) {
    return createCustomType(name, null, fields);
  }

  BaseType createCustomType(String name, String supertype, List<TypedField> fields) {
    if (Types.Builtin.hasType(name)) {
      throw new IllegalArgumentException();
    }
    return new CustomType(
        typeCounter.getAndIncrement(),
        name,
        supertype,
        fields,
        // TODO hack for event types not to go to constant pool
        !"jdk.jfr.Event".equals(supertype) ? constantPools : null,
        types);
  }
}
