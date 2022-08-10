package datadog.trace.plugin.csi.impl;

import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.DUP2;
import static org.objectweb.asm.Opcodes.DUP2_X1;
import static org.objectweb.asm.Opcodes.DUP2_X2;
import static org.objectweb.asm.Opcodes.POP2;

import datadog.trace.plugin.csi.StackHandler;
import datadog.trace.plugin.csi.StackHandler.AbstractStackHandler;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * Implementation of {@link StackHandler} that delegates to other handlers and caches the resulting
 * values in a Map based cache.
 *
 * <p>There is an initial collection of precomputed common combinations defined by the method {@link
 * CacheableStackHandler#buildInitialCache()}
 */
public class CacheableStackHandler extends AbstractStackHandler {

  private static final int[] NOT_RESOLVABLE = new int[0];

  private final Map<String, Map<String, int[]>> cache = new HashMap<>();
  private final List<StackHandler> delegated;

  public CacheableStackHandler(@Nonnull final StackHandler... delegated) {
    this(buildInitialCache(), delegated);
  }

  public CacheableStackHandler(
      @Nonnull final Map<String, Map<String, int[]>> initialValues,
      @Nonnull final StackHandler... delegated) {
    cache.putAll(initialValues);
    this.delegated = Arrays.asList(delegated);
  }

  @Override
  @Nonnull
  public Optional<int[]> calculateInstructions(
      @Nonnull final StackEntry current, @Nonnull final StackEntry target) {
    if (current.equals(target)) {
      return Optional.of(NO_INSTRUCTIONS);
    }

    final Map<String, int[]> mappings =
        cache.computeIfAbsent(current.getValue(), key -> new HashMap<>());
    final int[] result = mappings.get(target.getValue());
    if (result != null) {
      return result == NOT_RESOLVABLE ? Optional.empty() : Optional.of(result);
    }

    for (StackHandler item : delegated) {
      final Optional<int[]> delegatedResult = item.calculateInstructions(current, target);
      if (delegatedResult.isPresent()) {
        mappings.put(target.getValue(), delegatedResult.get());
        return delegatedResult;
      }
    }

    mappings.put(target.getValue(), NOT_RESOLVABLE);
    return Optional.empty();
  }

  private static Map<String, Map<String, int[]>> buildInitialCache() {
    return new CacheBuilder()
        .from(0)
        .to(0, 0)
        .withInstructions(DUP)
        .next()
        .from(0, 1)
        .to(0, 1, 0, 1)
        .withInstructions(DUP2)
        .next()
        .from(0, 1, 2)
        .to(0, 1, 2, 0, 1, 2)
        .withInstructions(DUP, DUP2_X2, POP2, DUP2_X2, DUP2_X1, POP2)
        .next()
        .from(0, 1, 2, 3)
        .to(0, 1, 2, 3, 0, 1, 2, 3)
        .withInstructions(DUP2_X2, POP2, DUP2_X2, DUP2_X2, POP2, DUP2_X2)
        .next()
        .build();
  }

  private static class CacheBuilder {
    private final Map<String, Map<String, int[]>> cache = new HashMap<>();

    public ToBuilder from(@Nonnull final int... from) {
      return new ToBuilder(this, from);
    }

    public Map<String, Map<String, int[]>> build() {
      return cache;
    }

    private static class ToBuilder {
      private final CacheBuilder cacheBuilder;
      private final int[] from;

      private ToBuilder(@Nonnull final CacheBuilder cacheBuilder, @Nonnull final int[] from) {
        this.cacheBuilder = cacheBuilder;
        this.from = from;
      }

      public InstructionsBuilder to(@Nonnull final int... to) {
        return new InstructionsBuilder(this, to);
      }

      public CacheBuilder next() {
        return cacheBuilder;
      }
    }

    private static class InstructionsBuilder {
      private final int[] to;
      private final ToBuilder toBuilder;

      private InstructionsBuilder(@Nonnull final ToBuilder toBuilder, @Nonnull final int[] to) {
        this.toBuilder = toBuilder;
        this.to = to;
      }

      public ToBuilder withInstructions(@Nonnull final int... instructions) {
        String from =
            Arrays.stream(toBuilder.from)
                .mapToObj(Integer::toString)
                .collect(Collectors.joining(DELIMITER));
        String to =
            Arrays.stream(this.to)
                .mapToObj(Integer::toString)
                .collect(Collectors.joining(DELIMITER));
        toBuilder
            .cacheBuilder
            .cache
            .computeIfAbsent(from, key -> new HashMap<>())
            .put(to, instructions);
        return toBuilder;
      }
    }
  }
}
