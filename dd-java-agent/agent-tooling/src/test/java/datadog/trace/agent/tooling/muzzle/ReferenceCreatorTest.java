package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_INTERFACE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_INTERFACE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_PRIVATE;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_NON_STATIC;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_PUBLIC_OR_PROTECTED;
import static datadog.trace.agent.tooling.muzzle.Reference.EXPECTS_STATIC;
import static datadog.trace.agent.tooling.muzzle.ReferenceCreatorTestSupport.referencesFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.tooling.muzzle.TestAdviceClasses.InDyAdvice;
import datadog.trace.agent.tooling.muzzle.TestAdviceClasses.InstanceofAdvice;
import datadog.trace.agent.tooling.muzzle.TestAdviceClasses.LdcAdvice;
import datadog.trace.agent.tooling.muzzle.TestAdviceClasses.MethodBodyAdvice;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReferenceCreatorTest extends DDJavaSpecification {

  private static final String A_CLASS = MethodBodyAdvice.A.class.getName();
  private static final String B_CLASS = MethodBodyAdvice.B.class.getName();
  private static final String SOME_INTERFACE = MethodBodyAdvice.SomeInterface.class.getName();
  private static final String SOME_IMPLEMENTATION =
      MethodBodyAdvice.SomeImplementation.class.getName();
  private static final String HAS_METHOD = MethodBodyAdvice.HasMethod.class.getName();

  @Test
  void methodBodyCreatesReferences() {
    Map<String, Reference> references = referencesFrom(MethodBodyAdvice.class);

    assertNotNull(references.get(A_CLASS));
    assertNotNull(references.get(B_CLASS));
    assertNotNull(references.get(SOME_INTERFACE));
    assertNotNull(references.get(SOME_IMPLEMENTATION));
    assertEquals(4, references.size());

    // interface flags
    assertHasFlag(EXPECTS_NON_INTERFACE, references.get(B_CLASS).flags);
    assertHasFlag(EXPECTS_INTERFACE, references.get(SOME_INTERFACE).flags);

    // class access flags
    assertHasFlag(EXPECTS_NON_PRIVATE, references.get(A_CLASS).flags);
    assertHasFlag(EXPECTS_NON_PRIVATE, references.get(B_CLASS).flags);

    // method refs
    Reference.Method[] bMethods = references.get(B_CLASS).methods;
    Reference.Method aMethod =
        findMethod(bMethods, "aMethod", "(Ljava/lang/String;)Ljava/lang/String;");
    Reference.Method aStaticMethod = findMethod(bMethods, "aStaticMethod", "()V");
    assertNotNull(aMethod);
    assertNotNull(findMethod(bMethods, "aMethodWithPrimitives", "(Z)V"));
    assertNotNull(aStaticMethod);
    assertNotNull(
        findMethod(bMethods, "aMethodWithArrays", "([Ljava/lang/String;)[Ljava/lang/Object;"));

    assertHasFlag(EXPECTS_NON_STATIC, aMethod.flags);
    assertHasFlag(EXPECTS_STATIC, aStaticMethod.flags);

    // field refs
    assertEquals(0, references.get(B_CLASS).fields.length);
    Reference.Field[] aFieldRefs = references.get(A_CLASS).fields;
    Reference.Field bField = findField(aFieldRefs, "b");
    Reference.Field staticBField = findField(aFieldRefs, "staticB");
    assertNotNull(bField);
    assertNotNull(staticBField);
    assertHasFlag(EXPECTS_NON_PRIVATE, bField.flags);
    assertHasFlag(EXPECTS_NON_STATIC, bField.flags);
    assertHasFlag(EXPECTS_NON_PRIVATE, staticBField.flags);
    assertHasFlag(EXPECTS_STATIC, staticBField.flags);
    assertEquals(2, aFieldRefs.length);
  }

  @Test
  void ignoreInvocationOfObjectMethodsOnInterfaceAfterJdk8272715() {
    // After JDK 18/19 javac compiles differently Object methods, while there is a ignore mechanism
    // on java.* packages after the key change is described in
    // https://bugs.openjdk.org/browse/JDK-827271, object's methods are applied to interfaces which
    // may belong to another package.
    //
    // Key change
    // "invocations of java.lang.Object methods on interfaces in the classfile will use
    // invokeinterface referring to the interface, which is consistent with JLS 9.2. This will be
    // done regardless of whether the interface declares the method explicitly or not."

    Map<String, Reference> references =
        referencesFrom(CompiledWithInvokeinterfaceForObjectMethods.class);

    assertNull(
        references.get(
            CompiledWithInvokeinterfaceForObjectMethods.DatadogInterface.class.getName()));
  }

  @Test
  void protectedRefTest() {
    Map<String, Reference> references = referencesFrom(MethodBodyAdvice.B2.class);

    Reference.Method[] bMethods = references.get(B_CLASS).methods;
    Reference.Method protectedMethod = findMethod(bMethods, "protectedMethod", "()V");
    assertNotNull(protectedMethod);
    assertHasFlag(EXPECTS_PUBLIC_OR_PROTECTED, protectedMethod.flags);
  }

  @Test
  void ldcCreatesReferences() {
    Map<String, Reference> references = referencesFrom(LdcAdvice.class);

    assertNotNull(references.get(A_CLASS));
  }

  @Test
  void interfaceImplCreatesReferences() {
    Map<String, Reference> references = referencesFrom(MethodBodyAdvice.SomeImplementation.class);

    assertNotNull(references.get(SOME_INTERFACE));
    assertEquals(1, references.size());
  }

  @Test
  void childClassCreatesReferences() {
    Map<String, Reference> references = referencesFrom(MethodBodyAdvice.A2.class);

    assertNotNull(references.get(A_CLASS));
    assertEquals(1, references.size());
  }

  @Test
  void instanceofCreatesReferences() {
    Map<String, Reference> references = referencesFrom(InstanceofAdvice.class);

    assertNotNull(references.get(A_CLASS));
  }

  @Test
  void invokedynamicCreatesReferences() {
    Map<String, Reference> references = referencesFrom(InDyAdvice.class);

    assertNotNull(references.get(HAS_METHOD));
    assertNotNull(references.get(B_CLASS));
  }

  private static void assertHasFlag(int expectedFlag, int flags) {
    assertNotEquals(0, flags & expectedFlag, "expected flag " + expectedFlag + " in " + flags);
  }

  private static Reference.Method findMethod(
      Reference.Method[] methods, String methodName, String methodDesc) {
    Reference.Method expected = new Reference.Method(new String[0], 0, methodName, methodDesc);
    for (Reference.Method method : methods) {
      if (method.equals(expected)) {
        return method;
      }
    }
    return null;
  }

  private static Reference.Field findField(Reference.Field[] fields, String fieldName) {
    for (Reference.Field field : fields) {
      if (field.name.equals(fieldName)) {
        return field;
      }
    }
    return null;
  }
}
