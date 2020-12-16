package context

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.ClasspathUtils
import datadog.trace.api.Config
import datadog.trace.test.util.GCUtils
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.utility.JavaModule
import net.sf.cglib.proxy.Enhancer
import net.sf.cglib.proxy.MethodInterceptor
import net.sf.cglib.proxy.MethodProxy

import java.lang.instrument.ClassDefinition
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

import static context.FieldInjectionTestInstrumentation.*
import static org.junit.Assume.assumeTrue

class FieldInjectionLegacyForkedTest extends AgentTestRunner {
  void configurePreAgent() {
    injectSysConfig("dd.trace.legacy.context.field.injection", "true")
  }

  @Override
  boolean onInstrumentationError(
    final String typeName,
    final ClassLoader classLoader,
    final JavaModule module,
    final boolean loaded,
    final Throwable throwable) {
    // Incorrect* classes assert on incorrect api usage. Error expected.
    return !(typeName.startsWith(FieldInjectionTestInstrumentation.getName() + '$Incorrect') && throwable.getMessage().startsWith("Incorrect Context Api Usage detected."))
  }

  @Override
  protected boolean shouldTransformClass(final String className, final ClassLoader classLoader) {
    return className == null || (!className.endsWith("UntransformableKeyClass"))
  }

  def "#keyClassName structure modified = #shouldModifyStructure"() {
    setup:
    boolean hasField = false
    boolean isPrivate = false
    boolean isTransient = false
    for (Field field : keyClass.getDeclaredFields()) {
      if (field.getName().startsWith("__datadog")) {
        isPrivate = Modifier.isPrivate(field.getModifiers())
        isTransient = Modifier.isTransient(field.getModifiers())
        hasField = true
        break
      }
    }

    boolean hasMarkerInterface = false
    boolean hasAccessorInterface = false
    for (Class inter : keyClass.getInterfaces()) {
      if (inter.getName() == 'datadog.trace.bootstrap.FieldBackedContextStoreAppliedMarker') {
        hasMarkerInterface = true
      }
      if (inter.getName().startsWith('datadog.trace.bootstrap.instrumentation.context.FieldBackedProvider$ContextAccessor')) {
        hasAccessorInterface = true
      }
    }

    expect:
    hasField == shouldModifyStructure
    isPrivate == shouldModifyStructure
    isTransient == shouldModifyStructure
    hasMarkerInterface == shouldModifyStructure
    hasAccessorInterface == shouldModifyStructure
    keyClass.newInstance().isInstrumented() == isInstrumented

    where:
    keyClass                            | shouldModifyStructure | isInstrumented
    KeyClass                            | true                  | true
    UntransformableKeyClass             | false                 | false
    ValidSerializableKeyClass           | true                  | true
    InvalidSerializableKeyClass         | true                  | true
    ValidInheritsSerializableKeyClass   | true                  | true
    InvalidInheritsSerializableKeyClass | true                  | true

    keyClassName = keyClass.getSimpleName()
  }

  def "correct api usage stores state in map"() {
    when:
    instance1.incrementContextCount()

    then:
    instance1.incrementContextCount() == 2
    instance2.incrementContextCount() == 1

    where:
    instance1                     | instance2
    new KeyClass()                | new KeyClass()
    new UntransformableKeyClass() | new UntransformableKeyClass()
  }

  def "get/put test"() {
    when:
    instance1.putContextCount(10)

    then:
    instance1.getContextCount() == 10

    where:
    instance1                     | _
    new KeyClass()                | _
    new UntransformableKeyClass() | _
  }

  def "serializability not impacted"() {
    setup:
    assumeTrue(Config.get().isSerialVersionUIDFieldInjection())

    expect:
    serialVersionUID(serializable) == serialVersionUID

    where:
    serializable                        | serialVersionUID // These are calculated with the corresponding declarations in FieldInjectionTestInstrumentation removed
    ValidSerializableKeyClass           | 123
    InvalidSerializableKeyClass         | -5663127853206342441L
    ValidInheritsSerializableKeyClass   | 456
    InvalidInheritsSerializableKeyClass | -4774694079403599336L
  }

  static final long serialVersionUID(Class<? extends Serializable> klass) throws Exception {
    try {
      def field = klass.getDeclaredField("serialVersionUID")
      field.setAccessible(true)
      return (long) field.get(null)
    } catch (NoSuchFieldException ex) {
      def method = ObjectStreamClass.getDeclaredMethod("computeDefaultSUID", Class)
      method.setAccessible(true)
      return (long) method.invoke(null, klass)
    }
  }

  def "works with cglib enhanced instances which duplicates context getter and setter methods"() {
    setup:
    Enhancer enhancer = new Enhancer()
    enhancer.setSuperclass(KeyClass)
    enhancer.setCallback(new MethodInterceptor() {
      @Override
      Object intercept(Object arg0, Method arg1, Object[] arg2,
                       MethodProxy arg3) throws Throwable {
        return arg3.invokeSuper(arg0, arg2)
      }
    })

    when:
    (KeyClass) enhancer.create()

    then:
    noExceptionThrown()
  }

  def "backing map should not create strong refs to key class instances #keyValue.get().getClass().getName()"() {
    when:
    final int count = keyValue.get().incrementContextCount()
    WeakReference<KeyClass> instanceRef = new WeakReference(keyValue.get())
    keyValue.set(null)
    GCUtils.awaitGC(instanceRef)

    then:
    instanceRef.get() == null
    count == 1

    where:
    keyValue                                           | _
    new AtomicReference(new KeyClass())                | _
    new AtomicReference(new UntransformableKeyClass()) | _
  }

  def "context classes are retransform safe"() {
    when:
    ByteBuddyAgent.getInstrumentation().retransformClasses(KeyClass)
    ByteBuddyAgent.getInstrumentation().retransformClasses(UntransformableKeyClass)

    then:
    new KeyClass().isInstrumented()
    !new UntransformableKeyClass().isInstrumented()
    new KeyClass().incrementContextCount() == 1
    new UntransformableKeyClass().incrementContextCount() == 1
  }

  def "context classes are redefine safe"() {
    when:
    ByteBuddyAgent.getInstrumentation().redefineClasses(new ClassDefinition(KeyClass, ClasspathUtils.convertToByteArray(KeyClass)))
    ByteBuddyAgent.getInstrumentation().redefineClasses(new ClassDefinition(UntransformableKeyClass, ClasspathUtils.convertToByteArray(UntransformableKeyClass)))

    then:
    new KeyClass().isInstrumented()
    !new UntransformableKeyClass().isInstrumented()
    new KeyClass().incrementContextCount() == 1
    new UntransformableKeyClass().incrementContextCount() == 1
  }

  def "incorrect key class usage fails at class load time"() {
    expect:
    !new IncorrectKeyClassUsageKeyClass().isInstrumented()
  }

  def "incorrect context class usage fails at class load time"() {
    expect:
    !new IncorrectContextClassUsageKeyClass().isInstrumented()
  }

  def "incorrect call usage fails at class load time"() {
    expect:
    !new IncorrectCallUsageKeyClass().isInstrumented()
  }
}

/**
 * Make sure that fields don't get injected into the class if it is disabled via system properties.
 */
class FieldInjectionDisabledLegacyForkedTest extends AgentTestRunner {
  void configurePreAgent() {
    injectSysConfig("dd.trace.legacy.context.field.injection", "true")
    injectSysConfig("dd.trace.runtime.context.field.injection", "false")
  }

  def "Check that structure is not modified when structure modification is disabled"() {
    setup:
    def keyClass = DisabledKeyClass
    boolean hasField = false
    for (Field field : keyClass.getDeclaredFields()) {
      if (field.getName().startsWith("__datadog")) {
        hasField = true
        break
      }
    }

    boolean hasMarkerInterface = false
    boolean hasAccessorInterface = false
    for (Class inter : keyClass.getInterfaces()) {
      if (inter.getName() == 'datadog.trace.bootstrap.FieldBackedContextStoreAppliedMarker') {
        hasMarkerInterface = true
      }
      if (inter.getName().startsWith('datadog.trace.bootstrap.instrumentation.context.FieldBackedProvider$ContextAccessor')) {
        hasAccessorInterface = true
      }
    }

    expect:
    hasField == false
    hasMarkerInterface == false
    hasAccessorInterface == false
    keyClass.newInstance().isInstrumented() == true
  }

}
