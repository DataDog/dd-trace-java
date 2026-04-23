package datadog.trace.instrumentation.jetty8;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import javax.servlet.http.Part;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.OpenedClassReader;

@AutoService(InstrumenterModule.class)
public class RequestGetPartsInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RequestGetPartsInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".PartHelper"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getParts").and(takesArguments(0)), getClass().getName() + "$GetFilenamesAdvice");
    transformer.applyAdvice(
        named("getPart").and(takesArguments(1)).and(takesArgument(0, String.class)),
        getClass().getName() + "$GetPartAdvice");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return RequestImplementationClassLoaderMatcher.INSTANCE;
  }

  public static class RequestImplementationClassLoaderMatcher
      extends ElementMatcher.Junction.ForNonNullValues<ClassLoader> {
    public static final ElementMatcher.Junction<ClassLoader> INSTANCE =
        new RequestImplementationClassLoaderMatcher();

    @Override
    protected boolean doMatch(ClassLoader cl) {
      try (InputStream is = cl.getResourceAsStream("org/eclipse/jetty/server/Request.class")) {
        if (is == null) {
          return false;
        }
        ClassReader classReader = new ClassReader(is);
        final boolean[] foundField = new boolean[1];
        final boolean[] foundGetParameters = new boolean[1];
        classReader.accept(new ClassLoaderMatcherClassVisitor(foundField, foundGetParameters), 0);
        return !foundField[0] && foundGetParameters[0];
      } catch (IOException e) {
        return false;
      }
    }
  }

  public static class ClassLoaderMatcherClassVisitor extends ClassVisitor {
    final boolean[] foundField;
    final boolean[] foundGetParameters;

    public ClassLoaderMatcherClassVisitor(boolean[] foundField, boolean[] foundGetParameters) {
      super(OpenedClassReader.ASM_API);
      this.foundField = foundField;
      this.foundGetParameters = foundGetParameters;
    }

    @Override
    public FieldVisitor visitField(
        int access, String name, String descriptor, String signature, Object value) {
      if (name.equals("_contentParameters")) {
        foundField[0] = true;
      }
      return null;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      if (name.equals("getParts") && "()Ljava/util/Collection;".equals(descriptor)) {
        return new MethodVisitor(OpenedClassReader.ASM_API) {
          @Override
          public void visitMethodInsn(
              int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                && name.equals("getParameters")
                && descriptor.equals("()Lorg/eclipse/jetty/util/MultiMap;")) {
              foundGetParameters[0] = true;
            }
          }
        };
      }
      return null;
    }
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetFilenamesAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before(
        @Advice.FieldValue(value = "_multiPartInputStream", typing = Assigner.Typing.DYNAMIC)
            final Object multiPartInputStream) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(Collection.class);
      // _multiPartInputStream is null before the first parse; non-null on cached repeat calls.
      return callDepth == 0 && multiPartInputStream == null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.Return Collection<?> parts,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Collection.class);
      if (!proceed || t != null || parts == null || parts.isEmpty()) {
        return;
      }
      t = PartHelper.fireBodyProcessedEvent(parts, reqCtx);
      if (t == null) {
        t = PartHelper.fireFilenamesEvent(parts, reqCtx);
      }
    }
  }

  /**
   * Fires AppSec events for a single-part upload via {@code getPart(String)}, which in Jetty 8.x
   * does NOT delegate to {@code getParts()} — it calls {@code
   * _multiPartInputStream.getPart(String)} directly. Without this advice, single-file uploads that
   * never call the public {@code getParts()} would be missed.
   */
  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class GetPartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static boolean before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Part.class) == 0;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(
        @Advice.Enter boolean proceed,
        @Advice.Return Part part,
        @ActiveRequestContext RequestContext reqCtx,
        @Advice.Thrown(readOnly = false) Throwable t) {
      CallDepthThreadLocalMap.decrementCallDepth(Part.class);
      if (!proceed || t != null || part == null) {
        return;
      }
      Collection<Part> parts = Collections.singletonList(part);
      t = PartHelper.fireBodyProcessedEvent(parts, reqCtx);
      if (t == null) {
        t = PartHelper.fireFilenamesEvent(parts, reqCtx);
      }
    }
  }
}
