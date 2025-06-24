package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Map;

public abstract class TestInstrumentationClasses {
  static final Reference[] SOME_ADVICE_REFS;

  static {
    Map<String, Reference> references =
        ReferenceCreator.createReferencesFrom(
            SomeAdvice.class.getName(), SomeAdvice.class.getClassLoader());
    SOME_ADVICE_REFS = references.values().toArray(new Reference[0]);
  }

  public abstract static class BaseInst extends InstrumenterModule
      implements Instrumenter.HasMethodAdvice {

    public BaseInst() {
      super("test");
    }

    @Override
    public TargetSystem targetSystem() {
      return TargetSystem.COMMON;
    }

    @Override
    public void methodAdvice(MethodTransformer transformer) {}
  }

  public static class EmptyInst extends BaseInst {
    public static class Muzzle {
      public static ReferenceMatcher create() {
        return ReferenceMatcher.NO_REFERENCES;
      }
    }
  }

  public static class ValidHelperInst extends BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {
        HelperClass.class.getName(), HelperClass.NestedHelperClass.class.getName(),
      };
    }

    public static class Muzzle {
      public static ReferenceMatcher create() {
        return ReferenceMatcher.NO_REFERENCES;
      }
    }
  }

  public static class InvalidOrderHelperInst extends BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {
        HelperClass.NestedHelperClass.class.getName(), HelperClass.class.getName(),
      };
    }

    public static class Muzzle {
      public static ReferenceMatcher create() {
        return ReferenceMatcher.NO_REFERENCES;
      }
    }
  }

  public static class InvalidMissingHelperInst extends BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {
        HelperClass.NestedHelperClass.class.getName(),
      };
    }

    public static class Muzzle {
      public static ReferenceMatcher create() {
        return ReferenceMatcher.NO_REFERENCES;
      }
    }
  }

  public static class BasicInst extends BaseInst {
    public static class Muzzle {
      public static ReferenceMatcher create() {
        return new ReferenceMatcher(SOME_ADVICE_REFS);
      }
    }
  }

  public static class HelperInst extends BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {
        AdviceParameter.class.getName(),
        AdviceMethodReturn.class.getName(),
        AdviceReference.class.getName(),
        AdviceStaticReference.class.getName(),
      };
    }

    public static class Muzzle {
      public static ReferenceMatcher create() {
        return new ReferenceMatcher(SOME_ADVICE_REFS);
      }
    }
  }

  public static class SomeAdvice {
    private AdviceMethodReturn test(AdviceParameter param) {
      new AdviceReference().doSomething();
      AdviceStaticReference.doSomething();
      return null;
    }
  }

  public interface AdviceParameter {}

  public interface AdviceMethodReturn {}

  public static class AdviceReference {
    public void doSomething() {}
  }

  public static class AdviceStaticReference {
    public static void doSomething() {}
  }
}
