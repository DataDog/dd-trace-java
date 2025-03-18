package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_INTERFACE_METHODREF_TAG;
import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_METHODREF_TAG;

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.agent.tooling.csi.InvokeAdvice;
import datadog.trace.agent.tooling.csi.InvokeDynamicAdvice;
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

  private final Listener[] listeners;

  private Advices(
      final Map<String, Map<String, Map<String, CallSiteAdvice>>> advices,
      final String[] helpers,
      final AdviceIntrospector introspector,
      final Listener... listeners) {
    this.advices = Collections.unmodifiableMap(advices);
    this.helpers = helpers;
    this.introspector = introspector;
    this.listeners = listeners;
  }

  public static Advices fromCallSites(@Nonnull final CallSites... callSites) {
    return fromCallSites(Arrays.asList(callSites));
  }

  public static Advices fromCallSites(
      @Nonnull final Iterable<CallSites> callSites, final Listener... listeners) {
    return fromCallSites(
        callSites, AdviceIntrospector.ConstantPoolInstrospector.INSTANCE, listeners);
  }

  public static Advices fromCallSites(
      @Nonnull final Iterable<CallSites> callSites,
      @Nonnull final AdviceIntrospector introspector,
      final Listener... listeners) {
    final AdviceContainer container = new AdviceContainer();
    for (final CallSites entry : callSites) {
      if (isCallSitesEnabled(entry)) {
        entry.accept(container);
      }
    }
    return container.advices.isEmpty()
        ? EMPTY
        : new Advices(
            container.advices, container.helpers.toArray(new String[0]), introspector, listeners);
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
    return introspector.findAdvices(this, builder, type, loader, listeners);
  }

  private static boolean isCallSitesEnabled(final CallSites callSites) {
    if (!(callSites instanceof CallSites.HasEnabledProperty)) {
      return true;
    }
    final CallSites.HasEnabledProperty hasEnabledProperty =
        (CallSites.HasEnabledProperty) callSites;
    return hasEnabledProperty.isEnabled();
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

  /** Gets the type of advice we are dealing with */
  public byte typeOf(final CallSiteAdvice advice) {
    return ((TypedAdvice) advice).getType();
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
        final byte type,
        final String owner,
        final String method,
        final String descriptor,
        final CallSiteAdvice advice) {
      final Map<String, Map<String, CallSiteAdvice>> typeAdvices =
          advices.computeIfAbsent(owner, k -> new HashMap<>());
      final Map<String, CallSiteAdvice> methodAdvices =
          typeAdvices.computeIfAbsent(method, k -> new HashMap<>());
      final CallSiteAdvice oldAdvice =
          methodAdvices.put(descriptor, TypedAdvice.withType(advice, type));
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
    Advices findAdvices(
        @Nonnull Advices advices,
        @Nonnull DynamicType.Builder<?> builder,
        @Nonnull TypeDescription type,
        ClassLoader loader,
        Listener... listeners);

    class NoOpAdviceInstrospector implements AdviceIntrospector {

      public static final AdviceIntrospector INSTANCE = new NoOpAdviceInstrospector();

      @Override
      public @Nonnull Advices findAdvices(
          final @Nonnull Advices advices,
          final @Nonnull DynamicType.Builder<?> builder,
          @Nonnull final TypeDescription type,
          final ClassLoader loader,
          final Listener... listeners) {
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
          final @Nonnull Advices advices,
          final @Nonnull DynamicType.Builder<?> builder,
          @Nonnull final TypeDescription type,
          final ClassLoader loader,
          final Listener... listeners) {
        byte[] classFile = resolveFromBuilder(type, builder);
        if (classFile == null) {
          classFile = resolveFromLoader(type, loader);
        }
        if (classFile == null) {
          return advices; // do not do any filtering if we don't have access to the class file
          // buffer
        }
        final ConstantPool cp = new ConstantPool(classFile);
        for (final Listener listener : listeners) {
          try {
            listener.onConstantPool(type, cp, classFile);
          } catch (final Throwable e) {
            LOG.debug("Failed to apply advice listener {}", listener, e);
          }
        }
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
       * + * Try to fetch the class file buffer from the actual builder via introspection to improve
       * + * performance +
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
      private byte[] resolveFromLoader(
          @Nonnull final TypeDescription type, final ClassLoader loader) {
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

  public interface Listener {
    void onConstantPool(
        @Nonnull TypeDescription type, @Nonnull ConstantPool pool, final byte[] classFile);
  }

  private interface TypedAdvice {
    byte getType();

    static CallSiteAdvice withType(final CallSiteAdvice advice, final byte type) {
      if (advice instanceof InvokeAdvice) {
        return new InvokeWithType((InvokeAdvice) advice, type);
      } else {
        return new InvokeDynamicWithType((InvokeDynamicAdvice) advice, type);
      }
    }
  }

  private static class InvokeWithType implements InvokeAdvice, TypedAdvice {
    private final InvokeAdvice advice;
    private final byte type;

    private InvokeWithType(InvokeAdvice advice, byte type) {
      this.advice = advice;
      this.type = type;
    }

    @Override
    public byte getType() {
      return type;
    }

    @Override
    public void apply(
        final MethodHandler handler,
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      advice.apply(handler, opcode, owner, name, descriptor, isInterface);
    }
  }

  private static class InvokeDynamicWithType implements InvokeDynamicAdvice, TypedAdvice {
    private final InvokeDynamicAdvice advice;
    private final byte type;

    private InvokeDynamicWithType(final InvokeDynamicAdvice advice, final byte type) {
      this.advice = advice;
      this.type = type;
    }

    @Override
    public byte getType() {
      return type;
    }

    @Override
    public void apply(
        final MethodHandler handler,
        final String name,
        final String descriptor,
        final Handle bootstrapMethodHandle,
        final Object... bootstrapMethodArguments) {
      advice.apply(handler, name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
    }
  }
}
