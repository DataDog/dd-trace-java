package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MetadataTest {
  private static final String TYPE_NAME = "dummy.Type";

  private Metadata instance;

  @BeforeEach
  public void setup() throws Exception {
    instance = new Metadata(new ConstantPools());
  }

  @Test
  public void registerBuiltinNull() {
    try {
      instance.registerBuiltin(null);
      Assertions.fail("Null built-in type may not be registered");
    } catch (NullPointerException ignored) {
    }
  }

  @Test
  public void registerBuiltin() {
    for (Types.Builtin builtin : Types.Builtin.values()) {
      instance.registerBuiltin(builtin);
      // the builtin must be immediately available
      assertNotNull(instance.getType(builtin.getTypeName(), false));
    }
  }

  @Test
  public void resolveTypes() {
    // try to get a non-existing type
    JFRType resolvable = instance.getType(TYPE_NAME, true);
    assertFalse(resolvable.isResolved());
    assertEquals(TYPE_NAME, resolvable.getTypeName());

    // register the type in metadata
    instance.registerType(
        TYPE_NAME, null, () -> new TypeStructure(Collections.emptyList(), Collections.emptyList()));
    // and resolve the resolvable wrapper
    instance.resolveTypes();

    assertTrue(resolvable.isResolved());
    assertEquals(TYPE_NAME, resolvable.getTypeName());
  }

  @Test
  public void registerTypeNullStructureProvider() {
    assertNotNull(instance.registerType(TYPE_NAME, "type.Super", null));
  }

  @Test
  public void registerType() {
    JFRType type = instance.registerType(TYPE_NAME, "type.Super", () -> TypeStructure.EMPTY);
    assertNotNull(type);
    assertNotNull(type.getFields());
    assertNotNull(type.getAnnotations());
  }

  @Test
  public void getTypeEmptyMetadata() {
    assertNull(instance.getType("dummy.Type", false));
  }

  @Test
  public void getResolvableTypeEmptyMetadata() {
    assertNotNull(instance.getType("dummy.Type", true));
  }

  @Test
  void createInvalidBuiltinType() {
    try {
      String typeName = "invalid";
      instance.createBuiltinType(typeName);
      fail("Should not be able to create a built-in type with name '" + typeName + "'");
    } catch (IllegalArgumentException ignored) {
    }
  }

  @Test
  void createValidBuiltinType() {
    String typeName = Types.Builtin.BYTE.getTypeName();
    JFRType type = instance.createBuiltinType(typeName);
    assertNotNull(type);
    assertTrue(type.isBuiltin());
    assertEquals(typeName, type.getTypeName());
  }

  @Test
  void createDuplicateBuiltinType() {
    String typeName = Types.Builtin.BYTE.getTypeName();
    JFRType type1 = instance.createBuiltinType(typeName);
    assertNotNull(type1);
    assertTrue(type1.isBuiltin());

    JFRType type2 = instance.createBuiltinType(typeName);
    assertNotNull(type2);
    assertTrue(type2.isBuiltin());
    assertEquals(type1.getTypeName(), type2.getTypeName());
    assertNotEquals(type1, type2);
  }

  @Test
  void createCustomTypeForBuiltin() {
    try {
      String typeName = Types.Builtin.BYTE.getTypeName();
      instance.createCustomType(typeName, null, TypeStructure.EMPTY);
      fail("Should not be able to create a custom type with name '" + typeName + "'");
    } catch (IllegalArgumentException ignored) {
    }
  }

  @Test
  void createCustomTypeNullStructure() {
    String typeName = "dummy.Test";
    JFRType type = instance.createCustomType(typeName, null, null);
    assertNotNull(type);
    assertFalse(type.isBuiltin());
    assertEquals(typeName, type.getTypeName());
    assertNotNull(type.getFields());
    assertNotNull(type.getAnnotations());
  }

  @Test
  void createCustomType() {
    String typeName = "dummy.Test";
    String superName = "super.Type";
    JFRType type = instance.createCustomType(typeName, superName, TypeStructure.EMPTY);
    assertNotNull(type);
    assertFalse(type.isBuiltin());
    assertEquals(typeName, type.getTypeName());
    assertNotNull(type.getFields());
    assertNotNull(type.getAnnotations());
    assertEquals(superName, type.getSupertype());
  }

  @Test
  void createDuplicateCustomType() {
    String typeName = "dummy.Test";
    String superName = "super.Type";

    JFRType type1 = instance.createCustomType(typeName, superName, TypeStructure.EMPTY);
    assertNotNull(type1);
    assertFalse(type1.isBuiltin());

    JFRType type2 = instance.createCustomType(typeName, superName, TypeStructure.EMPTY);
    assertNotNull(type2);
    assertFalse(type2.isBuiltin());
    assertEquals(type1.getTypeName(), type2.getTypeName());
    assertNotEquals(type1, type2);
  }
}
