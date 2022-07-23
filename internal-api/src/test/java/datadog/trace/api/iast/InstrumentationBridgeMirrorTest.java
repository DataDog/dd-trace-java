package datadog.trace.api.iast;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.bytebuddy.ByteBuddy;
import org.junit.Assert;
import org.junit.Test;

public class InstrumentationBridgeMirrorTest {

  @Test
  public void verifyCompatibility() {
    assertThat(InstrumentationBridge.class.getModifiers())
        .isEqualTo(InstrumentationBridgeShortCircuit.class.getModifiers());
    Field[] fields = InstrumentationBridge.class.getDeclaredFields();
    Field[] mirrorf = InstrumentationBridgeShortCircuit.class.getDeclaredFields();
    assertThat(fields.length).isEqualTo(mirrorf.length);

    for (int i = 0; i < fields.length; i++) {
      assertThat(fields[i].getName()).isEqualTo(mirrorf[i].getName());
    }

    Method[] methods = InstrumentationBridge.class.getMethods();
    for (Method method : methods) {
      try {
        Method mirror =
            InstrumentationBridgeShortCircuit.class.getMethod(
                method.getName(), method.getParameterTypes());
        assertThat(method.getReturnType()).isEqualTo(mirror.getReturnType());
        assertThat(method.getModifiers()).isEqualTo(mirror.getModifiers());
      } catch (NoSuchMethodException e) {
        Assert.fail("Method not replicated:" + method.getName());
      }
    }
  }

  @Test
  public void verifyTransformation() {
    ByteBuddy buddy = new ByteBuddy();
    String name = InstrumentationBridge.class.getName() + "2";
    Class<?> redefined =
        buddy
            .redefine(InstrumentationBridgeShortCircuit.class)
            .name(name)
            .make()
            .load(InstrumentationBridge.class.getClassLoader())
            .getLoaded();
    Assert.assertEquals(name, redefined.getName());
  }
}
