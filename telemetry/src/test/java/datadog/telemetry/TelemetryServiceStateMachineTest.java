package datadog.telemetry;

import static datadog.telemetry.TelemetryClient.Result.FAILURE;
import static datadog.telemetry.TelemetryClient.Result.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.telemetry.api.Integration;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;

class TelemetryServiceStateMachineTest {

  private static final Pattern EVENT_ID = Pattern.compile("\\\"name\\\":\\\"event-(\\d+)\\\"");

  private enum Command {
    ADD,
    SEND_SUCCESS,
    SEND_FAILURE
  }

  private enum Finding {
    REQUEST_OWNERSHIP_MISMATCH,
    MORE_DATA_SIGNAL_MISMATCH
  }

  @Test
  void generatedRetrySequencesPreserveAcceptedEventOwnership() {
    Map<Finding, Counterexample> distinctFindings = new EnumMap<>(Finding.class);

    for (List<Command> commands : generatedSequences(5)) {
      Counterexample counterexample = execute(commands);
      if (counterexample != null) {
        distinctFindings.putIfAbsent(counterexample.finding, counterexample);
      }
    }

    assertTrue(
        distinctFindings.isEmpty(),
        () ->
            "telemetry state machine found distinct counterexamples (shortest schedule per cause): "
                + distinctFindings.values());
  }

  private static Counterexample execute(List<Command> commands) {
    RecordingRouter router = new RecordingRouter();
    TelemetryService service = new TelemetryService(router, 1_000_000, false);
    Set<Integer> queued = new LinkedHashSet<>();
    Set<Integer> retryBuffered = null;
    int nextEventId = 0;

    for (int index = 0; index < commands.size(); index++) {
      Command command = commands.get(index);
      if (command == Command.ADD) {
        int id = nextEventId++;
        service.addIntegration(new Integration("event-" + id, true));
        queued.add(id);
        continue;
      }

      boolean retrying = retryBuffered != null;
      Set<Integer> expectedRequest = new LinkedHashSet<>(retrying ? retryBuffered : queued);
      TelemetryClient.Result result = command == Command.SEND_SUCCESS ? SUCCESS : FAILURE;
      router.nextResult(result);
      boolean actualMoreData = service.sendTelemetryEvents();
      Set<Integer> actualRequest = router.lastRequestEventIds();

      if (!expectedRequest.equals(actualRequest)) {
        return new Counterexample(
            Finding.REQUEST_OWNERSHIP_MISMATCH,
            commands,
            index,
            expectedRequest,
            actualRequest,
            !queued.isEmpty(),
            actualMoreData);
      }

      if (retrying) {
        retryBuffered = null;
      } else {
        queued.removeAll(expectedRequest);
        if (result == FAILURE && !expectedRequest.isEmpty()) {
          retryBuffered = expectedRequest;
        }
      }

      boolean expectedMoreData = result == SUCCESS && !queued.isEmpty();
      if (expectedMoreData != actualMoreData) {
        return new Counterexample(
            Finding.MORE_DATA_SIGNAL_MISMATCH,
            commands,
            index,
            expectedRequest,
            actualRequest,
            expectedMoreData,
            actualMoreData);
      }
    }
    return null;
  }

  private static List<List<Command>> generatedSequences(int maximumLength) {
    List<List<Command>> sequences = new ArrayList<>();
    for (int length = 1; length <= maximumLength; length++) {
      generate(length, new ArrayList<>(), sequences);
    }
    sequences.removeIf(
        sequence ->
            !sequence.contains(Command.ADD)
                || (!sequence.contains(Command.SEND_SUCCESS)
                    && !sequence.contains(Command.SEND_FAILURE)));
    return sequences;
  }

  private static void generate(int remaining, List<Command> prefix, List<List<Command>> sequences) {
    if (remaining == 0) {
      sequences.add(new ArrayList<>(prefix));
      return;
    }
    for (Command command : EnumSet.allOf(Command.class)) {
      prefix.add(command);
      generate(remaining - 1, prefix, sequences);
      prefix.remove(prefix.size() - 1);
    }
  }

  private static final class RecordingRouter extends TelemetryRouter {
    private final Queue<TelemetryClient.Result> results = new ArrayDeque<>();
    private Set<Integer> lastRequestEventIds;

    private RecordingRouter() {
      super(null, null, null, false);
    }

    private void nextResult(TelemetryClient.Result result) {
      results.add(result);
    }

    @Override
    public TelemetryClient.Result sendRequest(TelemetryRequest request) {
      lastRequestEventIds = eventIds(request);
      return results.remove();
    }

    private Set<Integer> lastRequestEventIds() {
      return lastRequestEventIds;
    }

    private static Set<Integer> eventIds(TelemetryRequest telemetryRequest) {
      try {
        Request.Builder requestBuilder = telemetryRequest.httpRequest();
        requestBuilder.url("https://example.com");
        Request request = requestBuilder.build();
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        String body = buffer.readUtf8();
        Set<Integer> ids = new LinkedHashSet<>();
        Matcher matcher = EVENT_ID.matcher(body);
        while (matcher.find()) {
          ids.add(Integer.parseInt(matcher.group(1)));
        }
        return ids;
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static final class Counterexample {
    private final Finding finding;
    private final List<Command> commands;
    private final int commandIndex;
    private final Set<Integer> expectedRequest;
    private final Set<Integer> actualRequest;
    private final boolean expectedMoreData;
    private final boolean actualMoreData;

    private Counterexample(
        Finding finding,
        List<Command> commands,
        int commandIndex,
        Set<Integer> expectedRequest,
        Set<Integer> actualRequest,
        boolean expectedMoreData,
        boolean actualMoreData) {
      this.finding = finding;
      this.commands = new ArrayList<>(commands);
      this.commandIndex = commandIndex;
      this.expectedRequest = new LinkedHashSet<>(expectedRequest);
      this.actualRequest = new LinkedHashSet<>(actualRequest);
      this.expectedMoreData = expectedMoreData;
      this.actualMoreData = actualMoreData;
    }

    @Override
    public String toString() {
      return finding
          + ": "
          + commands
          + "; commandIndex="
          + commandIndex
          + ", expectedRequest="
          + expectedRequest
          + ", actualRequest="
          + actualRequest
          + ", expectedMoreData="
          + expectedMoreData
          + ", actualMoreData="
          + actualMoreData;
    }
  }
}
