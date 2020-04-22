package com.datadog.profiling.jfr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class Metadata {
  private final TypeFactory factory;
  private final Map<String, Type> metadata = new HashMap<>();
  private final Map<String, Integer> stringTable = new HashMap<>();
  private final Map<Integer, String> reverseStringTable = new TreeMap<>();
  private final Set<ResolvableType> unresolvedTypes = new HashSet<>();

  Metadata(TypeFactory factory) {
    this.factory = factory;
    fillStrings();
  }

  private void fillStrings() {
    storeString("1");
    storeString("class");
    storeString("field");
    storeString("name");
    storeString("id");
    storeString("value");
    storeString("superType");
    storeString("constantPool");
    storeString("simpleType");
    storeString("root");
    storeString("metadata");
    storeString("true");
    storeString("region");
    storeString("dimension");
  }

  void registerBuiltin(Types.Builtin typeDef) {
    Type type = metadata.computeIfAbsent(typeDef.getTypeName(), factory::createBuiltinType);
    storeTypeStrings(type);
  }

  void resolveTypes() {
    unresolvedTypes.removeIf(ResolvableType::resolve);
  }

  public Type registerType(String typeName, List<TypedField> fieldStructure) {
    return registerType(typeName, null, fieldStructure);
  }

  public Type registerType(String typeName, String supertype, List<TypedField> fieldStructure) {
    Type type = metadata.get(typeName);
    if (type == null) {
      type = factory.createCustomType(typeName, supertype, fieldStructure);
      metadata.put(typeName, type);
    }
    storeTypeStrings(type);
    return type;
  }

  public Type registerType(String typeName, Supplier<List<TypedField>> fieldStructureProvider) {
    return registerType(typeName, null, fieldStructureProvider);
  }

  public Type registerType(
      String typeName, String supertype, Supplier<List<TypedField>> fieldStructureProvider) {
    Type type = metadata.get(typeName);
    if (type == null) {
      type = factory.createCustomType(typeName, supertype, fieldStructureProvider.get());
      metadata.put(typeName, type);
    }
    storeTypeStrings(type);
    return type;
  }

  private void storeTypeStrings(Type type) {
    storeString(type.getTypeName());
    if (type.getSupertype() != null) {
      storeString(type.getSupertype());
    }
    storeString(String.valueOf(type.getId()));
    for (TypedField field : type.getFields()) {
      storeString(field.getName());
    }
  }

  public Type getType(String name, boolean asResolvable) {
    Type found = metadata.get(name);
    if (found == null) {
      if (asResolvable) {
        found = new ResolvableType(name, this);
        unresolvedTypes.add((ResolvableType) found);
      }
    }
    return found;
  }

  private void storeString(String value) {
    stringTable.computeIfAbsent(
        value,
        k -> {
          int pointer = stringTable.size();
          reverseStringTable.put(pointer, k);
          return pointer;
        });
  }

  private int stringIndex(String value) {
    // TODO handle NPE
    return stringTable.get(value);
  }

  void writeMetaEvent(ByteArrayWriter writer, long startTs, long duration) {
    ByteArrayWriter metaWriter = new ByteArrayWriter(4096);
    metaWriter
        .writeLong(0L) // metadata event id
        .writeLong(startTs)
        .writeLong(duration)
        .writeLong(0L)
        .writeInt(stringTable.size());

    for (String text : reverseStringTable.values()) {
      metaWriter.writeUTF(text);
    }
    metaWriter
        .writeInt(stringIndex("root"))
        .writeInt(0) // 0 attributes
        .writeInt(2) // 1 elemen
        .writeInt(stringIndex("metadata"))
        .writeInt(0) // 0 attributes
        .writeInt(metadata.size()); // metadata.size() elements
    for (Type type : metadata.values()) {
      writeType(metaWriter, type);
    }
    metaWriter
        .writeInt(stringIndex("region"))
        .writeInt(0) // 0 attributes
        .writeInt(0); // 0 elements

    int len = metaWriter.length();
    int extraLen = 0;
    do {
      extraLen = ByteArrayWriter.getPackedIntLen(len + extraLen);
    } while (ByteArrayWriter.getPackedIntLen(len + extraLen) != extraLen);
    writer
        .writeInt(len + extraLen) // write the meta event size
        .writeBytes(metaWriter.toByteArray());
  }

  private void writeType(ByteArrayWriter writer, Type type) {
    int attributes = 2;
    if (type.getSupertype() != null) {
      attributes++;
    }
    if (type.isSimple()) {
      attributes++;
    }
    writer
        .writeInt(stringIndex("class"))
        .writeInt(attributes)
        .writeInt(stringIndex("name"))
        .writeInt(stringIndex(type.getTypeName()))
        .writeInt(stringIndex("id"))
        .writeInt(stringIndex(String.valueOf(type.getId())));
    if (type.getSupertype() != null) {
      writer.writeInt(stringIndex("superType")).writeInt(stringIndex(type.getSupertype()));
    }
    if (type.isSimple()) {
      writer.writeInt(stringIndex("simpleType")).writeInt(stringIndex("true"));
    }
    writer.writeInt(type.getFields().size());
    for (TypedField field : type.getFields()) {
      writeField(writer, field);
    }
  }

  private void writeField(ByteArrayWriter writer, TypedField field) {
    writer.writeInt(stringIndex("field"));
    int attrCount = 2;

    // java.lang.String is special - it is using constant pool but is not marked as such
    boolean withConstantPool =
        !field.getType().getTypeName().equals("java.lang.String")
            && field.getType().hasConstantPool();
    if (withConstantPool) {
      attrCount++;
    }
    if (field.isArray()) {
      attrCount++;
    }
    writer
        .writeInt(attrCount)
        .writeInt(stringIndex("name"))
        .writeInt(stringIndex(field.getName()))
        .writeInt(stringIndex("class"))
        .writeInt(stringIndex(String.valueOf(field.getType().getId())));
    if (field.isArray()) {
      writer.writeInt(stringIndex("dimension")).writeInt(stringIndex("1"));
    }
    if (withConstantPool) {
      writer.writeInt(stringIndex("constantPool")).writeInt(stringIndex("true"));
    }
    writer.writeInt(0); // 0 elements
  }
}
