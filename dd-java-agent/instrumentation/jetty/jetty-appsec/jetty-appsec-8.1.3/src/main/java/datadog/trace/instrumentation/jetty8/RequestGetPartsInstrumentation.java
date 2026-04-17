package datadog.trace.instrumentation.jetty8;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;

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
      super(Opcodes.ASM9);
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
        return new MethodVisitor(Opcodes.ASM9) {
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
    static boolean before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Collection.class) == 0;
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

      // Fire requestBodyProcessed with form-field name→values extracted from parts
      Map<String, List<String>> formFields = PartHelper.extractFormFields(parts);
      if (!formFields.isEmpty()) {
        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, Object, Flow<Void>> bodyCallback =
            cbp.getCallback(EVENTS.requestBodyProcessed());
        if (bodyCallback != null) {
          Flow<Void> flow = bodyCallback.apply(reqCtx, formFields);
          Flow.Action action = flow.getAction();
          if (action instanceof Flow.Action.RequestBlockingAction) {
            Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
            BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
            if (brf != null) {
              brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
              if (t == null) {
                t = new BlockingException("Blocked request (multipart form fields)");
                reqCtx.getTraceSegment().effectivelyBlocked();
              }
            }
          }
        }
      }

      if (t != null) {
        return;
      }

      // Fire requestFilesFilenames with file-upload filenames extracted from parts
      List<String> filenames = PartHelper.extractFilenames(parts);
      if (!filenames.isEmpty()) {
        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCallback =
            cbp.getCallback(EVENTS.requestFilesFilenames());
        if (filenamesCallback != null) {
          Flow<Void> flow = filenamesCallback.apply(reqCtx, filenames);
          Flow.Action action = flow.getAction();
          if (action instanceof Flow.Action.RequestBlockingAction) {
            Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
            BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
            if (brf != null) {
              brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
              if (t == null) {
                t = new BlockingException("Blocked request (multipart file upload)");
                reqCtx.getTraceSegment().effectivelyBlocked();
              }
            }
          }
        }
      }
    }
  }
}
