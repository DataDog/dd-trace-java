package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_INTERFACE_METHODREF_TAG;
import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_METHODREF_TAG;

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSites;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.inline.AbstractInliningDynamicTypeBuilder;
import net.bytebuddy.jar.asm.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class holding a collection of {@link CallSiteAdvice} indexed by the declaring type, method name
 * and method description, it stores the collection of helper classes required by the advices.
 */
public class Advices {

  private static final Logger LOG = LoggerFactory.getLogger(Advices.class);

  public static final Advices EMPTY =
      new Advices(
          Collections.emptyMap(),
          new String[0],
          AdviceIntrospector.NoOpAdviceInstrospector.INSTANCE) {
        @Override
        public boolean isEmpty() {
          return true;
        }
      };

  private static final Field BUILDER_CLASS_LOCATOR_FIELD = resolveClassFileLocatorField();

  private final Map<String, Map<String, Map<String, CallSiteAdvice>>> advices;

  private final String[] helpers;

  private final AdviceIntrospector introspector;

  private Advices(
      final Map<String, Map<String, Map<String, CallSiteAdvice>>> advices,
      final String[] helpers,
      final AdviceIntrospector introspector) {
    this.advices = Collections.unmodifiableMap(advices);
    this.helpers = helpers;
    this.introspector = introspector;
  }

  public static Advices fromCallSites(@Nonnull final CallSites... callSites) {
    return fromCallSites(Arrays.asList(callSites));
  }

  public static Advices fromCallSites(@Nonnull final Iterable<CallSites> callSites) {
    return fromCallSites(callSites, AdviceIntrospector.ConstantPoolInstrospector.INSTANCE);
  }

  public static Advices fromCallSites(
      @Nonnull final Iterable<CallSites> callSites,
      @Nonnull final AdviceIntrospector introspector) {
    final AdviceContainer container = new AdviceContainer();
    for (final CallSites entry : callSites) {
      if (isCallSitesEnabled(entry)) {
        entry.accept(container);
      }
    }
    return container.advices.isEmpty()
        ? EMPTY
        : new Advices(container.advices, container.helpers.toArray(new String[0]), introspector);
  }

  /**
   * The method will try to discover the {@link CallSiteAdvice} that should be applied to a specific
   * type by using the assigned {@link AdviceIntrospector}
   */
  public Advices findAdvices(
      @Nonnull final DynamicType.Builder<?> builder,
      @Nonnull final TypeDescription type,
      final ClassLoader loader) {
    if (advices.isEmpty()) {
      return this;
    }
    byte[] classFile = resolveFromBuilder(type, builder);
    if (classFile == null) {
      classFile = resolveFromLoader(type, loader);
    }
    if (classFile == null) {
      return this; // do not do any filtering if we don't have access to the class file buffer
    }
    return introspector.findAdvices(this, classFile);
  }

  private static boolean isCallSitesEnabled(final CallSites callSites) {
    if (!(callSites instanceof CallSites.HasEnabledProperty)) {
      return true;
    }
    final CallSites.HasEnabledProperty hasEnabledProperty =
        (CallSites.HasEnabledProperty) callSites;
    return hasEnabledProperty.isEnabled();
  }

  /**
   * Try to fetch the class file buffer from the actual builder via introspection to improve
   * performance
   */
  private byte[] resolveFromBuilder(
      @Nonnull final TypeDescription type, @Nonnull final DynamicType.Builder<?> builder) {
    if (builder instanceof AbstractInliningDynamicTypeBuilder
        && BUILDER_CLASS_LOCATOR_FIELD != null) {
      try {
        final ClassFileLocator locator =
            (ClassFileLocator) BUILDER_CLASS_LOCATOR_FIELD.get(builder);
        final ClassFileLocator.Resolution resolution = locator.locate(type.getName());
        return resolution.isResolved() ? resolution.resolve() : null;
      } catch (Throwable e) {
        if (LOG.isWarnEnabled()) {
          LOG.warn("Failed to fetch type {} from builder", type.getName(), e);
        }
      }
    }
    return null;
  }

  /** Use the default class loader strategy to resolve the class file buffer */
  private byte[] resolveFromLoader(@Nonnull final TypeDescription type, final ClassLoader loader) {
    try (final ClassFileLocator locator = ClassFileLocators.classFileLocator(loader)) {
      final ClassFileLocator.Resolution resolution = locator.locate(type.getName());
      return resolution.isResolved() ? resolution.resolve() : null;
    } catch (Throwable e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Failed to fetch type {} from class loader", type.getName(), e);
      }
    }
    return null;
  }

  public CallSiteAdvice findAdvice(@Nonnull final Handle handle) {
    return findAdvice(handle.getOwner(), handle.getName(), handle.getDesc());
  }

  /**
   * The method tries to find the configured advice for the specific method call
   *
   * @param callee type owning the method
   * @param method method name
   * @param descriptor descriptor of the method
   * @return the advice or {@code null} if none found
   */
  public CallSiteAdvice findAdvice(
      @Nonnull final String callee,
      @Nonnull final String method,
      @Nonnull final String descriptor) {
    if (advices.isEmpty()) {
      return null;
    }
    final Map<String, Map<String, CallSiteAdvice>> typeAdvices = advices.get(callee);
    if (typeAdvices == null) {
      return null;
    }
    final Map<String, CallSiteAdvice> methodAdvices = typeAdvices.get(method);
    if (methodAdvices == null) {
      return null;
    }
    return methodAdvices.get(descriptor);
  }

  public String[] getHelpers() {
    return helpers;
  }

  public boolean isEmpty() {
    return advices.isEmpty();
  }

  private static Field resolveClassFileLocatorField() {
    Field field;
    try {
      field = AbstractInliningDynamicTypeBuilder.class.getDeclaredField("classFileLocator");
      field.setAccessible(true);
      return field;
    } catch (Throwable e) {
      LOG.error(
          "Failed to resolve field \"classFileLocator\" in {}",
          AbstractInliningDynamicTypeBuilder.class,
          e);
    }
    return null;
  }

  private static class AdviceContainer implements CallSites.Container {
    private final Map<String, Map<String, Map<String, CallSiteAdvice>>> advices = new HashMap<>();

    private final Set<String> helpers = new HashSet<>();

    @Override
    public void addHelpers(final String... helperClassNames) {
      Collections.addAll(helpers, helperClassNames);
    }

    @Override
    public void addAdvice(
        final String type,
        final String method,
        final String descriptor,
        final CallSiteAdvice advice) {
      final Map<String, Map<String, CallSiteAdvice>> typeAdvices =
          advices.computeIfAbsent(type, k -> new HashMap<>());
      final Map<String, CallSiteAdvice> methodAdvices =
          typeAdvices.computeIfAbsent(method, k -> new HashMap<>());
      final CallSiteAdvice oldAdvice = methodAdvices.put(descriptor, advice);
      if (oldAdvice != null) {
        throw new UnsupportedOperationException(
            String.format(
                "Advice %s and %s match the same pointcut, this is not yet supported",
                oldAdvice, advice));
      }
    }
  }

  /**
   * Instance of this class will try to discover the advices required to instrument a class before
   * visiting it
   */
  public interface AdviceIntrospector {

    @Nonnull
    Advices findAdvices(@Nonnull final Advices advices, @Nonnull final byte[] classFile);

    class NoOpAdviceInstrospector implements AdviceIntrospector {

      public static final AdviceIntrospector INSTANCE = new NoOpAdviceInstrospector();

      @Override
      public @Nonnull Advices findAdvices(
          final @Nonnull Advices advices, final @Nonnull byte[] classFile) {
        return advices;
      }
    }

    /**
     * This class will try to parse the constant pool of the class in order to discover if any
     * configured advices should be applied.
     */
    class ConstantPoolInstrospector implements AdviceIntrospector {

      public static final AdviceIntrospector INSTANCE = new ConstantPoolInstrospector();

      private static final Map<Integer, ConstantPoolHandler> CP_HANDLERS;

      static {
        final Map<Integer, ConstantPoolHandler> handlers = new HashMap<>(4);
        final ConstantPoolHandler methodRefHandler = new MethodRefHandler();
        handlers.put(CONSTANT_METHODREF_TAG, methodRefHandler);
        handlers.put(CONSTANT_INTERFACE_METHODREF_TAG, methodRefHandler);
        CP_HANDLERS = Collections.unmodifiableMap(handlers);
      }

      @Override
      public @Nonnull Advices findAdvices(
          final @Nonnull Advices advices, final @Nonnull byte[] classFile) {
        final ConstantPool cp = new ConstantPool(classFile);
        for (int index = 1; index < cp.getCount(); index++) {
          final int referenceType = cp.getType(index);
          final ConstantPoolHandler handler = CP_HANDLERS.get(referenceType);
          // short circuit when any advice is found
          if (handler != null && handler.findAdvice(advices, cp, index) != null) {
            return advices;
          }
        }
        return EMPTY;
      }

      /** Handler for a particular type of constant pool type (MethodRef, InvokeDynamic, ...) */
      private interface ConstantPoolHandler {
        CallSiteAdvice findAdvice(@Nonnull Advices advices, @Nonnull ConstantPool cp, int index);
      }

      /**
       * Extracts advices by looking at method refs in the constant pool
       *
       * @see <a
       *     href="https://docs.oracle.com/javase/specs/jvms/se15/html/jvms-4.html#jvms-4.4.2">Methodref_info</a>
       */
      private static class MethodRefHandler implements ConstantPoolHandler {

        @Override
        public CallSiteAdvice findAdvice(
            @Nonnull final Advices advices, @Nonnull final ConstantPool cp, final int index) {
          final int offset = cp.getOffset(index);

          // u2 class_index;
          final int classIndex = cp.readUnsignedShort(offset);
          final int classNameIndex = cp.readUnsignedShort(cp.getOffset(classIndex));
          final String className = cp.readUTF8(cp.getOffset(classNameIndex));
          final Map<String, Map<String, CallSiteAdvice>> calleeAdvices =
              advices.advices.get(className);
          if (calleeAdvices == null) {
            return null;
          }

          // u2 name_and_type_index;
          final int nameAndTypeIndex = cp.readUnsignedShort(offset + 2);
          final int nameAndTypeOffset = cp.getOffset(nameAndTypeIndex);
          final int nameIndex = cp.readUnsignedShort(nameAndTypeOffset);
          final String name = cp.readUTF8(cp.getOffset(nameIndex));
          final Map<String, CallSiteAdvice> methodAdvices = calleeAdvices.get(name);
          if (methodAdvices == null) {
            return null;
          }
          final int descriptorIndex = cp.readUnsignedShort(nameAndTypeOffset + 2);
          final String descriptor = cp.readUTF8(cp.getOffset(descriptorIndex));
          return methodAdvices.get(descriptor);
        }
      }
    }
  }
}
