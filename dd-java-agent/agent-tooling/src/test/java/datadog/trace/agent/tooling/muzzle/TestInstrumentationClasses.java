package datadog.trace.agent.tooling.muzzle;

import static java.util.Collections.emptyMap;
import static net.bytebuddy.matcher.ElementMatchers.none;

import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class TestInstrumentationClasses {
  static final Reference[] SOME_ADVICE_REFS;

  static {
    Map<String, Reference> references =
        ReferenceCreator.createReferencesFrom(
            SomeAdvice.class.getName(), SomeAdvice.class.getClassLoader());
    SOME_ADVICE_REFS = references.values().toArray(new Reference[0]);
  }

  public abstract static class BaseInst extends Instrumenter.Default {

    public BaseInst() {
      super("test");
    }

    @Override
    public ElementMatcher<? super TypeDescription> typeMatcher() {
      return none();
    }

    @Override
    public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
      return emptyMap();
    }
  }

  public static class EmptyInst extends BaseInst {

    @Override
    protected ReferenceMatcher getInstrumentationMuzzle() {
      // Couldn't figure out how to get the byte-buddy gradle plugin to apply here.
      return new ReferenceMatcher();
    }
  }

  public static class ValidHelperInst extends BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {
        HelperClass.class.getName(), HelperClass.NestedHelperClass.class.getName(),
      };
    }

    @Override
    protected ReferenceMatcher getInstrumentationMuzzle() {
      // Couldn't figure out how to get the byte-buddy gradle plugin to apply here.
      return new ReferenceMatcher();
    }
  }

  public static class InvalidOrderHelperInst extends BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {
        HelperClass.NestedHelperClass.class.getName(), HelperClass.class.getName(),
      };
    }

    @Override
    protected ReferenceMatcher getInstrumentationMuzzle() {
      // Couldn't figure out how to get the byte-buddy gradle plugin to apply here.
      return new ReferenceMatcher();
    }
  }

  public static class InvalidMissingHelperInst extends BaseInst {
    @Override
    public String[] helperClassNames() {
      return new String[] {
        HelperClass.NestedHelperClass.class.getName(),
      };
    }

    @Override
    protected ReferenceMatcher getInstrumentationMuzzle() {
      // Couldn't figure out how to get the byte-buddy gradle plugin to apply here.
      return new ReferenceMatcher();
    }
  }

  public static class BasicInst extends BaseInst {
    @Override
    protected ReferenceMatcher getInstrumentationMuzzle() {
      // Couldn't figure out how to get the byte-buddy gradle plugin to apply here.
      return new ReferenceMatcher(SOME_ADVICE_REFS);
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

    @Override
    protected ReferenceMatcher getInstrumentationMuzzle() {
      // Couldn't figure out how to get the byte-buddy gradle plugin to apply here.
      return new ReferenceMatcher(SOME_ADVICE_REFS);
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
