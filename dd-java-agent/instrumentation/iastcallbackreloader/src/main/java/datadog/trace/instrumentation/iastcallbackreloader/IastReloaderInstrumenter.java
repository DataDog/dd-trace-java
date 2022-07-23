package datadog.trace.instrumentation.iastcallbackreloader;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridgeShortCircuit;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

@AutoService(Instrumenter.class)
public class IastReloaderInstrumenter extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  private static final String TYPE = "datadog.trace.api.iast.InstrumentationBridge";

  private boolean shortCircuited = false;

  public IastReloaderInstrumenter() {
    super("instrumentationBridge");
  }

  @Override
  public String instrumentedType() {
    return TYPE;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {}

  private DynamicType.Builder<?> redefineMirror(ClassLoader loader) {
    ClassFileLocator compoundLocator =
        new ClassFileLocator.Compound(
            ClassFileLocator.ForClassLoader.of(getClass().getClassLoader()),
            ClassFileLocator.ForClassLoader.of(loader));

    return new ByteBuddy()
        .redefine(InstrumentationBridgeShortCircuit.class, compoundLocator)
        .name(TYPE);
  }

  @Override
  public AdviceTransformer transformer() {
    return new AdviceTransformer() {
      @Override
      public DynamicType.Builder<?> transform(
          DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          ClassLoader classLoader,
          JavaModule module) {
        if (!shortCircuited) {
          return builder;
        } else {
          return redefineMirror(classLoader);
        }
      }
    };
  }
}
