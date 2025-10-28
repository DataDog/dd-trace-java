package context

import datadog.trace.agent.test.AbortTransformationException
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.ClasspathUtils
import datadog.trace.api.InstrumenterConfig
import datadog.trace.test.util.GCUtils
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.utility.JavaModule
import net.sf.cglib.proxy.Enhancer
import net.sf.cglib.proxy.MethodInterceptor
import net.sf.cglib.proxy.MethodProxy
import spock.lang.IgnoreIf

import java.lang.instrument.ClassDefinition
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

import static context.FieldInjectionTestInstrumentation.DisabledKeyClass
import static context.FieldInjectionTestInstrumentation.IncorrectCallUsageKeyClass
import static context.FieldInjectionTestInstrumentation.IncorrectContextClassUsageKeyClass
import static context.FieldInjectionTestInstrumentation.IncorrectKeyClassUsageKeyClass
import static context.FieldInjectionTestInstrumentation.InvalidInheritsSerializableKeyClass
import static context.FieldInjectionTestInstrumentation.InvalidSerializableKeyClass
import static context.FieldInjectionTestInstrumentation.KeyClass
import static context.FieldInjectionTestInstrumentation.UntransformableKeyClass
import static context.FieldInjectionTestInstrumentation.ValidInheritsSerializableKeyClass
import static context.FieldInjectionTestInstrumentation.ValidSerializableKeyClass

class FieldInjectionForkedTest extends InstrumentationSpecification {

  @Override
  void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
    if (typeName?.endsWith("UntransformableKeyClass")) {
      throw new AbortTransformationException(
      "Aborting transform for class name = " + typeName + ", loader = " + classLoader)
    }

    super.onDiscovery(typeName, classLoader, module, loaded)
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

    boolean hasAccessorInterface = false
    for (Class inter : keyClass.getInterfaces()) {
      if (inter.getName() == 'datadog.trace.bootstrap.FieldBackedContextAccessor') {
        hasAccessorInterface = true
      }
    }

    expect:
    hasField == shouldModifyStructure
    isPrivate == shouldModifyStructure
    isTransient == shouldModifyStructure
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

  def "correct api usage stores state in map #instance1.class.name"() {
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

  def "get/put test #instance1.class.name"() {
    when:
    instance1.putContextCount(10)
    instance1.putContextCount2(10)

    then:
    instance1.getContextCount() == 10
    instance1.getContextCount2() == 10

    where:
    instance1                     | _
    new KeyClass()                | _
    new UntransformableKeyClass() | _
  }

  @IgnoreIf({ !InstrumenterConfig.get().isSerialVersionUIDFieldInjection() })
  def "serializability not impacted #serializable"() {
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
    } catch (NoSuchFieldException ignored) {
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

  //@Flaky("awaitGC is flaky")
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
class FieldInjectionDisabledForkedTest extends InstrumentationSpecification {
  void configurePreAgent() {
    super.configurePreAgent()

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

    boolean hasAccessorInterface = false
    for (Class inter : keyClass.getInterfaces()) {
      if (inter.getName() == 'datadog.trace.bootstrap.FieldBackedContextAccessor') {
        hasAccessorInterface = true
      }
    }

    expect:
    hasField == false
    hasAccessorInterface == false
    keyClass.newInstance().isInstrumented() == true
  }
}
