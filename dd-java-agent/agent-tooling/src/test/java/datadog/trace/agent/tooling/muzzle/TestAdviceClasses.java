package datadog.trace.agent.tooling.muzzle;

import net.bytebuddy.asm.Advice;

public class TestAdviceClasses {

  public static class MethodBodyAdvice {
    @Advice.OnMethodEnter
    public static void methodBodyAdvice() {
      final A a = new A();
      final SomeInterface inter = new SomeImplementation();
      Object o = inter.requiredMethod();
      inter.someMethod();
      a.b.aMethod("foo");
      a.b.aMethodWithPrimitives(false);
      a.b.aMethodWithArrays(new String[0]);
      B.aStaticMethod();
      A.staticB.aMethod("bar");
      Object barr = new byte[0];
      if (barr instanceof byte[]) {
        barr = null;
      }
    }

    public static class A {
      public B b = new B();
      protected Object protectedField = null;
      private final Object privateField = null;
      public static B staticB = new B();
    }

    public static class B {
      public String aMethod(final String s) {
        return s;
      }

      public void aMethodWithPrimitives(final boolean b) {}

      public Object[] aMethodWithArrays(final String[] s) {
        return s;
      }

      private void privateStuff() {}

      protected void protectedMethod() {}

      public static void aStaticMethod() {}
    }

    public static class B2 extends B {
      public void stuff() {
        final B b = new B();
        b.protectedMethod();
      }
    }

    public static class A2 extends A {}

    public interface HasMethod {
      Object requiredMethod();
    }

    public interface SkipLevel extends HasMethod {}

    public interface SomeInterface extends SkipLevel {
      void someMethod();
    }

    public static class SomeImplementation implements SomeInterface {
      @Override
      public void someMethod() {}

      @Override
      public Object requiredMethod() {
        return null;
      }
    }

    public static class SomeClassWithFields {
      public int instanceField = 0;
      public static int staticField = 0;
      public final int finalField = 0;
    }

    public interface AnotherInterface extends SomeInterface {}
  }

  public static class LdcAdvice {
    public static void ldcMethod() {
      MethodBodyAdvice.A.class.getName();
    }
  }

  public static class InstanceofAdvice {
    public static boolean instanceofMethod(final Object a) {
      return a instanceof MethodBodyAdvice.A;
    }
  }

  // Can't test this until java 7 is dropped.
  public static class InDyAdvice {
    //    public static MethodBodyAdvice.HasMethod indyMethod(final MethodBodyAdvice.HasMethod a) {
    //      Runnable aStaticMethod = MethodBodyAdvice.B::aStaticMethod;
    //      return a::requiredMethod;
    //    }
  }
}
