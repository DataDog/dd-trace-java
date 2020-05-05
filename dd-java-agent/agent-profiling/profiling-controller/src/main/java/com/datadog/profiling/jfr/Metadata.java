package com.datadog.profiling.jfr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/** JFR type repository class. */
final class Metadata {
  private static final String CLASS_KEY = "class";
  private static final String FIELD_KEY = "field";
  private static final String NAME_KEY = "name";
  private static final String ID_KEY = "id";
  private static final String VALUE_KEY = "value";
  private static final String SUPER_TYPE_KEY = "superType";
  private static final String CONSTANT_POOL_KEY = "constantPool";
  private static final String SIMPLE_TYPE_KEY = "simpleType";
  private static final String ROOT_KEY = "root";
  private static final String METADATA_KEY = "metadata";
  private static final String TRUE_VALUE = "true";
  private static final String REGION_KEY = "region";
  private static final String DIMENSION_KEY = "dimension";
  private static final String ANNOTATION_KEY = "annotation";
  private static final String VAL_1_VALUE = "1";

  private final TypeFactory factory;
  private final Map<String, JFRType> metadata = new HashMap<>();
  private final Map<String, Integer> stringTable = new HashMap<>();
  private final Map<Integer, String> reverseStringTable = new TreeMap<>();
  private final Set<ResolvableJFRType> unresolvedTypes = new HashSet<>();

  Metadata(TypeFactory factory) {
    this.factory = factory;
    fillStrings();
  }

  /** Pre-fill the string constant pool with all used constant strings */
  private void fillStrings() {
    storeString(VAL_1_VALUE);
    storeString(CLASS_KEY);
    storeString(FIELD_KEY);
    storeString(NAME_KEY);
    storeString(ID_KEY);
    storeString(VALUE_KEY);
    storeString(SUPER_TYPE_KEY);
    storeString(CONSTANT_POOL_KEY);
    storeString(SIMPLE_TYPE_KEY);
    storeString(ROOT_KEY);
    storeString(METADATA_KEY);
    storeString(TRUE_VALUE);
    storeString(REGION_KEY);
    storeString(DIMENSION_KEY);
    storeString(ANNOTATION_KEY);
  }

  /**
   * Register a built-in type
   *
   * @param typeDef a {@link com.datadog.profiling.jfr.Types.Builtin built-in} type
   */
  void registerBuiltin(Types.Builtin typeDef) {
    JFRType type = metadata.computeIfAbsent(typeDef.getTypeName(), factory::createBuiltinType);
    storeTypeStrings(type);
  }

  /**
   * Resolve all dangling unresolved {@link ResolvableJFRType resolvable types}. This needs to be
   * done if some of the type definitions are using forward references to not yet registered types.
   */
  void resolveTypes() {
    unresolvedTypes.removeIf(ResolvableJFRType::resolve);
  }

  JFRType registerType(
      String typeName, String supertype, Supplier<TypeStructure> typeStructureProvider) {
    JFRType type = metadata.get(typeName);
    if (type == null) {
      type = factory.createCustomType(typeName, supertype, typeStructureProvider.get());
      metadata.put(typeName, type);
    }
    storeTypeStrings(type);
    return type;
  }

  /**
   * Retrieve a type with the given name.
   *
   * @param name the type name
   * @param asResolvable if the type is not found to be registered should a {@link ResolvableJFRType
   *     resolvable} wrapper be returned instead?
   * @return the type of the given name
   */
  JFRType getType(String name, boolean asResolvable) {
    JFRType found = metadata.get(name);
    if (found == null) {
      if (asResolvable) {
        found = new ResolvableJFRType(name, this);
        unresolvedTypes.add((ResolvableJFRType) found);
      }
    }
    return found;
  }

  private void storeTypeStrings(JFRType type) {
    storeString(type.getTypeName());
    if (type.getSupertype() != null) {
      storeString(type.getSupertype());
    }
    storeString(String.valueOf(type.getId()));
    for (TypedField field : type.getFields()) {
      storeString(field.getName());
      storeAnnotationStrings(field.getAnnotations());
    }
    storeAnnotationStrings(type.getAnnotations());
  }

  private void storeAnnotationStrings(List<JFRAnnotation> annotations) {
    for (JFRAnnotation annotation : annotations) {
      if (annotation.value != null) {
        storeString(annotation.value);
      }
    }
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
    writeHeader(startTs, duration, metaWriter);

    writeStringConstants(metaWriter);
    writeTypes(metaWriter);
    writeRegion(metaWriter);

    writeMetaEventSize(metaWriter, writer);
  }

  private void writeMetaEventSize(ByteArrayWriter metaWriter, ByteArrayWriter writer) {
    int len = metaWriter.length();
    int extraLen = 0;
    do {
      extraLen = ByteArrayWriter.getPackedIntLen(len + extraLen);
    } while (ByteArrayWriter.getPackedIntLen(len + extraLen) != extraLen);
    writer
        .writeInt(len + extraLen) // write the meta event size
        .writeBytes(metaWriter.toByteArray());
  }

  private void writeRegion(ByteArrayWriter metaWriter) {
    metaWriter
        .writeInt(stringIndex(REGION_KEY))
        .writeInt(0) // 0 attributes
        .writeInt(0); // 0 elements
  }

  private void writeTypes(ByteArrayWriter metaWriter) {
    metaWriter
        .writeInt(stringIndex(ROOT_KEY))
        .writeInt(0) // 0 attributes
        .writeInt(2) // 1 element
        .writeInt(stringIndex(METADATA_KEY))
        .writeInt(0) // 0 attributes
        .writeInt(metadata.size()); // metadata.size() elements
    for (JFRType type : metadata.values()) {
      writeType(metaWriter, type);
    }
  }

  private void writeStringConstants(ByteArrayWriter metaWriter) {
    for (String text : reverseStringTable.values()) {
      metaWriter.writeUTF(text);
    }
  }

  private void writeHeader(long startTs, long duration, ByteArrayWriter metaWriter) {
    metaWriter
        .writeLong(0L) // metadata event id
        .writeLong(startTs)
        .writeLong(duration)
        .writeLong(0L)
        .writeInt(stringTable.size());
  }

  private void writeType(ByteArrayWriter writer, JFRType type) {
    int attributes = 2;
    if (type.getSupertype() != null) {
      attributes++;
    }
    if (type.isSimple()) {
      attributes++;
    }
    writer
        .writeInt(stringIndex(CLASS_KEY))
        .writeInt(attributes)
        .writeInt(stringIndex(NAME_KEY))
        .writeInt(stringIndex(type.getTypeName()))
        .writeInt(stringIndex(ID_KEY))
        .writeInt(stringIndex(String.valueOf(type.getId())));
    if (type.getSupertype() != null) {
      writer.writeInt(stringIndex(SUPER_TYPE_KEY)).writeInt(stringIndex(type.getSupertype()));
    }
    if (type.isSimple()) {
      writer.writeInt(stringIndex(SIMPLE_TYPE_KEY)).writeInt(stringIndex(TRUE_VALUE));
    }
    writer.writeInt(type.getFields().size() + type.getAnnotations().size());
    writeTypeFields(writer, type);
    writeTypeAnnotations(writer, type);
  }

  private void writeTypeFields(ByteArrayWriter writer, JFRType type) {
    for (TypedField field : type.getFields()) {
      writeField(writer, field);
    }
  }

  private void writeTypeAnnotations(ByteArrayWriter writer, JFRType type) {
    for (JFRAnnotation annotation : type.getAnnotations()) {
      writeAnnotation(writer, annotation);
    }
  }

  private void writeField(ByteArrayWriter writer, TypedField field) {
    writer.writeInt(stringIndex(FIELD_KEY));
    int attrCount = 2;

    // java.lang.String is special - it is using constant pool but is not marked as such
    boolean withConstantPool =
        !field.getType().isSame(Types.Builtin.STRING) && field.getType().hasConstantPool();
    if (withConstantPool) {
      attrCount++;
    }
    if (field.isArray()) {
      attrCount++;
    }
    writer
        .writeInt(attrCount)
        .writeInt(stringIndex(NAME_KEY))
        .writeInt(stringIndex(field.getName()))
        .writeInt(stringIndex(CLASS_KEY))
        .writeInt(stringIndex(String.valueOf(field.getType().getId())));
    if (field.isArray()) {
      writer.writeInt(stringIndex(DIMENSION_KEY)).writeInt(stringIndex(VAL_1_VALUE));
    }
    if (withConstantPool) {
      writer.writeInt(stringIndex(CONSTANT_POOL_KEY)).writeInt(stringIndex(TRUE_VALUE));
    }
    writeFieldAnnotations(writer, field);
  }

  private void writeFieldAnnotations(ByteArrayWriter writer, TypedField field) {
    writer.writeInt(field.getAnnotations().size()); // annotations are the only sub-elements
    for (JFRAnnotation annotation : field.getAnnotations()) {
      writeAnnotation(writer, annotation);
    }
  }

  private void writeAnnotation(ByteArrayWriter writer, JFRAnnotation annotation) {
    writer.writeInt(stringIndex(ANNOTATION_KEY));

    writer
      .writeInt(annotation.value != null ? 2 : 1) // number of attributes
      .writeInt(stringIndex(CLASS_KEY))
      .writeInt(stringIndex(String.valueOf(annotation.type.getId())));
    if (annotation.value != null) {
      writer
        .writeInt(stringIndex(VALUE_KEY))
        .writeInt(stringIndex(annotation.value));
    }
    writer.writeInt(0); // no sub-elements
  }
}
