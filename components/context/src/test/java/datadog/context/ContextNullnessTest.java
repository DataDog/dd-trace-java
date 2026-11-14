package datadog.context;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ContextNullnessTest {
  @Test
  void typeAnnotationsCanBeReadAtRuntime() throws ReflectiveOperationException {
    for (Method method : Context.class.getDeclaredMethods()) {
      method.getAnnotatedReturnType();
      method.getAnnotatedParameterTypes();
    }
    assertNotNull(
        Context.class
            .getMethod("get", ContextKey.class)
            .getAnnotatedReturnType()
            .getAnnotation(Nullable.class));
    AnnotatedArrayType store =
        (AnnotatedArrayType) IndexedContext.class.getDeclaredField("store").getAnnotatedType();
    assertNull(store.getAnnotation(Nullable.class));
    assertNotNull(store.getAnnotatedGenericComponentType().getAnnotation(Nullable.class));
  }
}
