package datadog.trace.api.iast;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@Fork(value = 3)
@Measurement(iterations = 4, time = 5)
@Warmup(iterations = 3, time = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HelperInvocationBenchmark {

  interface HelpersI {
    void helperMeth(Blackhole bh, String s);
  }

  public static class HelpersImpl implements HelpersI {
    @Override
    public void helperMeth(Blackhole bh, String s) {
      bh.consume(s);
    }
  }

  public static class StaticBridge {
    static HelpersI helpers;

    public static void callHelper(Blackhole bh, String s) {
      if (helpers != null) {
        try {
          helpers.helperMeth(bh, s);
        } catch (Throwable t) {
          System.out.println("Helper threw");
        }
      }
    }
  }

  public static class HelperContainer implements InvokeDynamicHelperContainer {
    @InvokeDynamicHelper
    public static void helperMeth(Blackhole bh, String s) {
      bh.consume(s);
    }
  }

  @State(Scope.Benchmark)
  public static class StateClass {
    private final CallPseudoAdviceI callPseudoAdvice;

    public StateClass() {
      StaticBridge.helpers = new HelpersImpl();
      InvokeDynamicHelperRegistry.reset();
      InvokeDynamicHelperRegistry.registerHelperContainer(
          MethodHandles.lookup(), HelperContainer.class);
      String cpaClassName =
          HelperInvocationBenchmark.class.getPackage().getName() + ".CallPseudoAdvice";
      try {
        ByteArrayClassLoader cl =
            new ByteArrayClassLoader(
                StateClass.class.getClassLoader(),
                Collections.singletonMap(cpaClassName, CallPseudoAdviceDump.dump()));
        Class<?> cpaCls = cl.loadClass(cpaClassName);
        callPseudoAdvice = (CallPseudoAdviceI) cpaCls.newInstance();
      } catch (Exception e) {
        throw new UndeclaredThrowableException(e);
      }
    }
  }

  public interface CallPseudoAdviceI {
    void callStatic(Blackhole bh, String s);

    void callDynamic(Blackhole bh, String s);
  }

  @Benchmark
  public void callHelperStatic(StateClass cls, Blackhole bh) {
    cls.callPseudoAdvice.callStatic(bh, "my string");
  }

  @Benchmark
  public void callHelperDynamic(StateClass cls, Blackhole bh) {
    cls.callPseudoAdvice.callDynamic(bh, "my string");
  }

  public static void main(String[] args) throws Exception {
    StateClass stateClass = new StateClass();
    Blackhole bh =
        new Blackhole(
            "Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
    stateClass.callPseudoAdvice.callStatic(bh, "my string");
    stateClass.callPseudoAdvice.callDynamic(bh, "my string");
  }
}
