package datadog.appsec.benchmark;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.appsec.event.ChangeableFlow;
import com.datadog.appsec.event.DataListener;
import com.datadog.appsec.event.EventDispatcher;
import com.datadog.appsec.event.OrderedCallback;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.DataBundle;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.gateway.AppSecRequestContext;
import com.datadog.appsec.gateway.GatewayContext;
import java.util.Collections;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@Warmup(iterations = 4, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@Fork(value = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
public class EventDispatcherBenchmark {

  @State(Scope.Benchmark)
  public static class DispatcherState {
    public static final OrderedCallback.Priority[] PRIORITY_VALUES =
        OrderedCallback.Priority.values();

    @Param({"5", "50", "500"})
    int numUsedSubscribers;

    @Param({"5", "5000"})
    int numUnusedSubscribers;

    Address<?>[] usedAddresses =
        new Address<?>[] {
          KnownAddresses.REQUEST_BODY_RAW,
          KnownAddresses.REQUEST_COOKIES,
          KnownAddresses.REQUEST_QUERY
        };

    Address<?> unusedAddress = KnownAddresses.HEADERS_NO_COOKIES;

    EventDispatcher dispatcher = new EventDispatcher();

    @Setup
    public void create() {
      int iUsed = 0, iUnused = 0, iTotal = 0;
      int usedAddressIdx = 0;

      EventDispatcher.DataSubscriptionSet subsSet = new EventDispatcher.DataSubscriptionSet();
      while (iUsed < numUsedSubscribers || iUnused < numUnusedSubscribers) {
        if (iUsed < numUsedSubscribers) {
          iUsed++;
          int i = iTotal++;
          Address<?> usedAddress = usedAddresses[usedAddressIdx++ % usedAddresses.length];
          doSubscribe(subsSet, usedAddress, i);
        }
        if (iUnused < numUnusedSubscribers) {
          iUnused++;
          int i = iTotal++;
          doSubscribe(subsSet, unusedAddress, i);
        }
      }

      dispatcher.subscribeDataAvailable(subsSet);
    }

    private void doSubscribe(
        EventDispatcher.DataSubscriptionSet subsSet, Address<?> address, int i) {
      final OrderedCallback.Priority priority = PRIORITY_VALUES[i % 4];
      subsSet.addSubscription(
          Collections.singletonList(address),
          new DataListener() {
            @Override
            public void onDataAvailable(
                ChangeableFlow flow,
                AppSecRequestContext context,
                DataBundle dataBundle,
                GatewayContext gatewayContext) {}

            @Override
            public Priority getPriority() {
              return priority;
            }
          });
    }

    void run() {
      dispatcher.getDataSubscribers();
    }
  }

  @Benchmark
  public void getDataSubscribers(DispatcherState state) {
    state.run();
  }
}
