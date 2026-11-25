package datadog.trace.civisibility.coverage.probe;

import java.util.HashMap;
import java.util.Map;

/**
 * Constants added after a class file's own, which keep their indices. A constant added twice gets
 * one entry; one equal to an existing constant of the class gets a new entry, which is valid.
 */
final class ConstantPoolAppender {

  private static final int MAX_CONSTANTS = 0xFFFF;

  private final ClassFile classFile;
  private final ByteArrayBuilder entries = new ByteArrayBuilder(256);
  private final Map<String, Integer> added = new HashMap<>();
  private final Map<Integer, String> addedClassNames = new HashMap<>();

  /** Class constants by name, those of the class first; built when first needed. */
  private Map<String, Integer> classNames;

  private int next;

  ConstantPoolAppender(ClassFile classFile) {
    this.classFile = classFile;
    this.next = classFile.constantCount;
  }

  /** The constant count of the class file with the added constants. */
  int constantCount() {
    return next;
  }

  void writeTo(ByteArrayBuilder out) {
    out.putBytes(entries.data, 0, entries.length);
  }

  /** The index of a UTF8 constant of the class with that ASCII text, or a new one. */
  int existingOrNewUtf8(String ascii) {
    for (int i = 1; i < classFile.constantCount; i++) {
      if (classFile.constantOffset[i] != 0 && classFile.utf8Equals(i, ascii)) {
        return i;
      }
    }
    return utf8(ascii);
  }

  int utf8(String value) {
    String key = "1:" + value;
    Integer index = added.get(key);
    if (index != null) {
      return index;
    }
    int start = entries.length;
    entries.putByte(ClassFile.CONSTANT_UTF8).putShort(0);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 0x0001 && c <= 0x007F) {
        entries.putByte(c);
      } else if (c <= 0x07FF) {
        entries.putByte(0xC0 | ((c >> 6) & 0x1F)).putByte(0x80 | (c & 0x3F));
      } else {
        entries
            .putByte(0xE0 | ((c >> 12) & 0xF))
            .putByte(0x80 | ((c >> 6) & 0x3F))
            .putByte(0x80 | (c & 0x3F));
      }
    }
    int length = entries.length - start - 3;
    if (length > 0xFFFF) {
      throw new IllegalArgumentException("UTF8 constant too long");
    }
    entries.setShort(start + 1, length);
    return add(key);
  }

  int classConstant(String internalName) {
    String key = "7:" + internalName;
    Integer index = added.get(key);
    if (index != null) {
      return index;
    }
    int name = utf8(internalName);
    entries.putByte(ClassFile.CONSTANT_CLASS).putShort(name);
    index = add(key);
    if (classNames != null) {
      classNames.put(internalName, index);
    }
    addedClassNames.put(index, internalName);
    return index;
  }

  /** The index of a class constant of the class with that name, or of a new one. */
  int existingOrNewClass(String internalName) {
    if (classNames == null) {
      classNames = new HashMap<>();
      for (int i = 1; i < classFile.constantCount; i++) {
        if (classFile.constantOffset[i] != 0 && classFile.tag(i) == ClassFile.CONSTANT_CLASS) {
          classNames.putIfAbsent(classFile.className(i), i);
        }
      }
      for (Map.Entry<Integer, String> entry : addedClassNames.entrySet()) {
        classNames.putIfAbsent(entry.getValue(), entry.getKey());
      }
    }
    Integer index = classNames.get(internalName);
    return index != null ? index : classConstant(internalName);
  }

  /** The name of a class constant of the class or added to it. */
  String className(int index) {
    return index < classFile.constantCount
        ? classFile.className(index)
        : addedClassNames.get(index);
  }

  int integer(int value) {
    String key = "3:" + value;
    Integer index = added.get(key);
    if (index != null) {
      return index;
    }
    entries.putByte(ClassFile.CONSTANT_INTEGER).putInt(value);
    return add(key);
  }

  int methodReference(String owner, String name, String descriptor) {
    return memberReference(ClassFile.CONSTANT_METHODREF, owner, name, descriptor);
  }

  int fieldReference(String owner, String name, String descriptor) {
    return memberReference(ClassFile.CONSTANT_FIELDREF, owner, name, descriptor);
  }

  private int memberReference(int tag, String owner, String name, String descriptor) {
    String key = tag + ":" + owner + '.' + name + descriptor;
    Integer index = added.get(key);
    if (index != null) {
      return index;
    }
    int ownerClass = classConstant(owner);
    String nameAndTypeKey = "12:" + name + ' ' + descriptor;
    Integer nameAndType = added.get(nameAndTypeKey);
    if (nameAndType == null) {
      int nameIndex = utf8(name);
      int descriptorIndex = utf8(descriptor);
      entries
          .putByte(ClassFile.CONSTANT_NAME_AND_TYPE)
          .putShort(nameIndex)
          .putShort(descriptorIndex);
      nameAndType = add(nameAndTypeKey);
    }
    entries.putByte(tag).putShort(ownerClass).putShort(nameAndType);
    return add(key);
  }

  private int add(String key) {
    if (next >= MAX_CONSTANTS) {
      throw new IllegalArgumentException("too many constants");
    }
    int index = next++;
    added.put(key, index);
    return index;
  }
}
