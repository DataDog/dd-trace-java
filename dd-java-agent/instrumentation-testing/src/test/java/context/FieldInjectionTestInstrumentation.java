package context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class FieldInjectionTestInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public FieldInjectionTestInstrumentation() {
    super("fieldinjection-test");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // no particular marker type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith(getClass().getName() + "$");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("isInstrumented"), MarkInstrumentedAdvice.class.getName());
    transformer.applyAdvice(
        named("incrementContextCount"), StoreAndIncrementApiUsageAdvice.class.getName());
    transformer.applyAdvice(named("getContextCount"), GetApiUsageAdvice.class.getName());
    transformer.applyAdvice(named("putContextCount"), PutApiUsageAdvice.class.getName());
    transformer.applyAdvice(named("getContextCount2"), GetApiUsageAdvice2.class.getName());
    transformer.applyAdvice(named("putContextCount2"), PutApiUsageAdvice2.class.getName());
    transformer.applyAdvice(
        named("incorrectKeyClassUsage"), IncorrectKeyClassContextApiUsageAdvice.class.getName());
    transformer.applyAdvice(
        named("incorrectContextClassUsage"),
        IncorrectContextClassContextApiUsageAdvice.class.getName());
    transformer.applyAdvice(
        named("incorrectCallUsage"), IncorrectCallContextApiUsageAdvice.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {getClass().getName() + "$Context"};
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> store = new HashMap<>();
    String prefix = getClass().getName() + "$";
    store.put(prefix + "KeyClass", prefix + "Context");
    store.put(prefix + "UntransformableKeyClass", prefix + "Context");
    store.put(prefix + "DisabledKeyClass", prefix + "Context");
    store.put(prefix + "ValidSerializableKeyClass", prefix + "Context");
    store.put(prefix + "InvalidSerializableKeyClass", prefix + "Context");
    store.put(prefix + "ValidInheritsSerializableKeyClass", prefix + "Context");
    store.put(prefix + "InvalidInheritsSerializableKeyClass", prefix + "Context");
    return store;
  }

  public static class MarkInstrumentedAdvice {
    @Advice.OnMethodExit
    public static void methodExit(@Advice.Return(readOnly = false) boolean isInstrumented) {
      isInstrumented = true;
    }
  }

  public static class StoreAndIncrementApiUsageAdvice {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.This final KeyClass thiz, @Advice.Return(readOnly = false) int contextCount) {
      final ContextStore<KeyClass, Context> contextStore =
          InstrumentationContext.get(KeyClass.class, Context.class);
      final Context context = contextStore.putIfAbsent(thiz, new Context());
      contextCount = ++context.count;
    }
  }

  public static class StoreAndIncrementWithFactoryApiUsageAdvice {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.This final KeyClass thiz, @Advice.Return(readOnly = false) int contextCount) {
      final ContextStore<KeyClass, Context> contextStore =
          InstrumentationContext.get(KeyClass.class, Context.class);
      final Context context = contextStore.putIfAbsent(thiz, Context::new);
      contextCount = ++context.count;
    }
  }

  public static class GetApiUsageAdvice {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.This final KeyClass thiz, @Advice.Return(readOnly = false) int contextCount) {
      final ContextStore<KeyClass, Context> contextStore =
          InstrumentationContext.get(KeyClass.class, Context.class);
      contextCount = contextStore.get(thiz).count;
    }
  }

  public static class PutApiUsageAdvice {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.This final KeyClass thiz, @Advice.Argument(0) final int value) {
      final ContextStore<KeyClass, Context> contextStore =
          InstrumentationContext.get(KeyClass.class, Context.class);
      final Context context = new Context();
      context.count = value;
      contextStore.put(thiz, context);
    }
  }

  public static class GetApiUsageAdvice2 {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.This final Object thiz, @Advice.Return(readOnly = false) int contextCount) {
      final ContextStore<Object, Context> contextStore =
          InstrumentationContext.get(
              "context.FieldInjectionTestInstrumentation$KeyClass",
              "context.FieldInjectionTestInstrumentation$Context");
      contextCount = contextStore.get(thiz).count;
    }
  }

  public static class PutApiUsageAdvice2 {
    @Advice.OnMethodExit
    public static void methodExit(
        @Advice.This final Object thiz, @Advice.Argument(0) final int value) {
      final ContextStore<Object, Context> contextStore =
          InstrumentationContext.get(
              "context.FieldInjectionTestInstrumentation$KeyClass",
              "context.FieldInjectionTestInstrumentation$Context");
      final Context context = new Context();
      context.count = value;
      contextStore.put(thiz, context);
    }
  }

  public static class IncorrectKeyClassContextApiUsageAdvice {
    @Advice.OnMethodExit
    public static void methodExit() {
      InstrumentationContext.get(Object.class, Context.class);
    }
  }

  public static class IncorrectContextClassContextApiUsageAdvice {
    @Advice.OnMethodExit
    public static void methodExit() {
      InstrumentationContext.get(KeyClass.class, Object.class);
    }
  }

  public static class IncorrectCallContextApiUsageAdvice {
    @Advice.OnMethodExit
    public static void methodExit() {
      // Our instrumentation doesn't handle variables being passed to InstrumentationContext.get,
      // so we make sure that this actually fails instrumentation.
      final Class clazz = null;
      InstrumentationContext.get(clazz, Object.class);
    }
  }

  public static class Context {
    int count = 0;
  }

  public static class KeyClass {
    public boolean isInstrumented() {
      // implementation replaced with test instrumentation
      return false;
    }

    public int incrementContextCount() {
      // implementation replaced with test instrumentation
      return -1;
    }

    public int incrementContextCountWithFactory() {
      // implementation replaced with test instrumentation
      return -1;
    }

    public int getContextCount() {
      // implementation replaced with test instrumentation
      return -1;
    }

    public void putContextCount(final int value) {
      // implementation replaced with test instrumentation
    }

    public int getContextCount2() {
      // implementation replaced with test instrumentation
      return -1;
    }

    public void putContextCount2(final int value) {
      // implementation replaced with test instrumentation
    }
  }

  /** A class which cannot be transformed by our instrumentation. */
  public static class UntransformableKeyClass extends KeyClass {
    @Override
    public boolean isInstrumented() {
      return false;
    }
  }

  /** A class that is used that field injection can be disabled. */
  public static class DisabledKeyClass extends KeyClass {
    @Override
    public boolean isInstrumented() {
      return false;
    }
  }

  /** A class that is serializable with serialVersionUID. */
  public static class ValidSerializableKeyClass extends KeyClass implements Serializable {
    private static final long serialVersionUID = 123;
  }

  /** A class that is serializable with no serialVersionUID. */
  public static class InvalidSerializableKeyClass extends KeyClass implements Serializable {}

  /** A class that inherits serializable with serialVersionUID. */
  public static class ValidInheritsSerializableKeyClass extends InvalidSerializableKeyClass {
    private static final long serialVersionUID = 456;
  }

  /** A class that inherits serializable with no serialVersionUID. */
  public static class InvalidInheritsSerializableKeyClass extends ValidSerializableKeyClass {}

  public static class IncorrectKeyClassUsageKeyClass {
    public boolean isInstrumented() {
      return false;
    }

    public int incorrectKeyClassUsage() {
      // instrumentation will not apply to this class because advice incorrectly uses context api
      return -1;
    }
  }

  public static class IncorrectContextClassUsageKeyClass {
    public boolean isInstrumented() {
      return false;
    }

    public int incorrectContextClassUsage() {
      // instrumentation will not apply to this class because advice incorrectly uses context api
      return -1;
    }
  }

  public static class IncorrectCallUsageKeyClass {
    public boolean isInstrumented() {
      return false;
    }

    public int incorrectCallUsage() {
      // instrumentation will not apply to this class because advice incorrectly uses context api
      return -1;
    }
  }
}
