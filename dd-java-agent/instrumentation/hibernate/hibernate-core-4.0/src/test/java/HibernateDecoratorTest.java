import static datadog.trace.instrumentation.hibernate.HibernateDecorator.DECORATOR;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import javax.persistence.Entity;
import org.junit.jupiter.api.Test;

class HibernateDecoratorTest {
  @Test
  void handlesNullAndExplicitEntityNames() {
    assertNull(DECORATOR.entityName(null));
    String name = new String("ExplicitEntity");
    assertSame(name, DECORATOR.entityName(name));
    assertEquals("AnotherEntity", DECORATOR.entityName("AnotherEntity"));
  }

  @Test
  void usesClassNameForDeclaredEntities() {
    assertEquals(TestEntity.class.getName(), DECORATOR.entityName(new TestEntity()));
    assertEquals(TestEntity.class.getName(), DECORATOR.entityName(new TestEntity()));
  }

  @Test
  void doesNotTreatSubclassesOrPlainObjectsAsDeclaredEntities() {
    assertNull(DECORATOR.entityName(new EntitySubclass()));
    assertNull(DECORATOR.entityName(new Object()));
    assertNull(DECORATOR.entityName(new Object()));
  }

  @Test
  void handlesEmptyAndNestedLists() {
    assertNull(DECORATOR.entityName(emptyList()));
    assertNull(DECORATOR.entityName(singletonList(null)));
    assertEquals(
        "NestedEntity", DECORATOR.entityName(singletonList(singletonList("NestedEntity"))));
  }

  @Test
  void resolvesTheCurrentFirstListElement() {
    List<Object> entities = new ArrayList<>();
    entities.add(new TestEntity());
    assertEquals(TestEntity.class.getName(), DECORATOR.entityName(entities));
    entities.set(0, "ExplicitEntity");
    assertEquals("ExplicitEntity", DECORATOR.entityName(entities));
    entities.set(0, new Object());
    assertNull(DECORATOR.entityName(entities));
  }

  @Test
  void prefersEntityAnnotationOverListContents() {
    EntityList entities = new EntityList();
    entities.add("OtherEntity");
    assertEquals(EntityList.class.getName(), DECORATOR.entityName(entities));
  }

  @Entity(name = "CustomEntityName")
  static class TestEntity {}

  static class EntitySubclass extends TestEntity {}

  @Entity
  static class EntityList extends ArrayList<Object> {}
}
