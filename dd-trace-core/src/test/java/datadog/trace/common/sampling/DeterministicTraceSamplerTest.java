package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.DDTraceId;
import datadog.trace.core.DDSpan;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

public class DeterministicTraceSamplerTest {

  @ParameterizedTest
  @MethodSource("knownValuesArguments")
  void testKnownValues(boolean expected, String traceId) {
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(0.5);
    DDSpan span = Mockito.mock(DDSpan.class);
    Mockito.when(span.getTraceId()).thenReturn(DDTraceId.from(traceId));

    boolean sampled = sampler.sample(span);

    assertEquals(expected, sampled);
  }

  static Stream<Arguments> knownValuesArguments() {
    return Stream.of(
        Arguments.of(false, "10428415896243638596"),
        Arguments.of(false, "11199607447739267382"),
        Arguments.of(false, "11273630029763932141"),
        Arguments.of(false, "11407674492757219439"),
        Arguments.of(false, "11792151447964398879"),
        Arguments.of(false, "12432680895096110463"),
        Arguments.of(false, "13126262220165910460"),
        Arguments.of(false, "13174268766980400525"),
        Arguments.of(false, "15505210698284655633"),
        Arguments.of(false, "15649472107743074779"),
        Arguments.of(false, "17204678798284737396"),
        Arguments.of(false, "17344948852394588913"),
        Arguments.of(false, "17496662575514578077"),
        Arguments.of(false, "18252401681137062077"),
        Arguments.of(false, "18317291550776694829"),
        Arguments.of(false, "1874068156324778273"),
        Arguments.of(false, "1905388747193831650"),
        Arguments.of(false, "2202916659517317514"),
        Arguments.of(false, "2227583514184312746"),
        Arguments.of(false, "2338498362660772719"),
        Arguments.of(false, "2781055864473387780"),
        Arguments.of(false, "3328451335138149956"),
        Arguments.of(false, "3337066551442961397"),
        Arguments.of(false, "3409814636252858217"),
        Arguments.of(false, "3510942875414458836"),
        Arguments.of(false, "3784560248718450071"),
        Arguments.of(false, "4751997750760398084"),
        Arguments.of(false, "4831389563158288344"),
        Arguments.of(false, "4990765271833742716"),
        Arguments.of(false, "5089134323978233018"),
        Arguments.of(false, "5199948958991797301"),
        Arguments.of(false, "5577006791947779410"),
        Arguments.of(false, "5600924393587988459"),
        Arguments.of(false, "5793183108815074904"),
        Arguments.of(false, "6263450610539110790"),
        Arguments.of(false, "6382800227808658932"),
        Arguments.of(false, "6651414131918424343"),
        Arguments.of(false, "6842348953158377901"),
        Arguments.of(false, "6941261091797652072"),
        Arguments.of(false, "7273596521315663110"),
        Arguments.of(false, "7504504064263669287"),
        Arguments.of(false, "788787457839692041"),
        Arguments.of(false, "7955079406183515637"),
        Arguments.of(false, "8549944162621642512"),
        Arguments.of(false, "8603989663476771718"),
        Arguments.of(false, "8807817071862113702"),
        Arguments.of(false, "9010467728050264449"),
        Arguments.of(true, "10667007354186551956"),
        Arguments.of(true, "10683692646452562431"),
        Arguments.of(true, "10821471013040158923"),
        Arguments.of(true, "10950412492527322440"),
        Arguments.of(true, "11239168150708129139"),
        Arguments.of(true, "1169089424364679180"),
        Arguments.of(true, "11818186001859264308"),
        Arguments.of(true, "11833901312327420776"),
        Arguments.of(true, "11926759511765359899"),
        Arguments.of(true, "11926873763676642186"),
        Arguments.of(true, "11963748953446345529"),
        Arguments.of(true, "11998794077335055257"),
        Arguments.of(true, "12096659438561119542"),
        Arguments.of(true, "12156940908066221323"),
        Arguments.of(true, "12947799971452915849"),
        Arguments.of(true, "13260572831089785859"),
        Arguments.of(true, "13771804148684671731"),
        Arguments.of(true, "14117161486975057715"),
        Arguments.of(true, "14242321332569825828"),
        Arguments.of(true, "14486903973548550719"),
        Arguments.of(true, "14967026985784794439"),
        Arguments.of(true, "15213854965919594827"),
        Arguments.of(true, "15352856648520921629"),
        Arguments.of(true, "15399114114227588261"),
        Arguments.of(true, "15595235597337683065"),
        Arguments.of(true, "16194613440650274502"),
        Arguments.of(true, "1687184559264975024"),
        Arguments.of(true, "17490665426807838719"),
        Arguments.of(true, "18218388313430417611"),
        Arguments.of(true, "2601737961087659062"),
        Arguments.of(true, "261049867304784443"),
        Arguments.of(true, "2740103009342231109"),
        Arguments.of(true, "2970700287221458280"),
        Arguments.of(true, "3916589616287113937"),
        Arguments.of(true, "4324745483838182873"),
        Arguments.of(true, "4937104021912138218"),
        Arguments.of(true, "5486140987150761883"),
        Arguments.of(true, "5944830206637008055"),
        Arguments.of(true, "6296367092202729479"),
        Arguments.of(true, "6334824724549167320"),
        Arguments.of(true, "6556961545928831643"),
        Arguments.of(true, "6735196588112087610"),
        Arguments.of(true, "7388428680384065704"),
        Arguments.of(true, "8249030965139585917"),
        Arguments.of(true, "837825985403119657"),
        Arguments.of(true, "8505906760983331750"),
        Arguments.of(true, "8674665223082153551"),
        Arguments.of(true, "894385949183117216"),
        Arguments.of(true, "898860202204764712"),
        Arguments.of(true, "9768663798983814715"),
        Arguments.of(true, "9828766684487745566"),
        Arguments.of(true, "9908585559158765387"),
        Arguments.of(true, "9956202364908137547"),
        Arguments.of(true, "9223372036854775808"));
  }

  @ParameterizedTest
  @MethodSource("samplingNoneArguments")
  void testSamplingNone(boolean expected, String traceId) {
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(0);
    DDSpan span = Mockito.mock(DDSpan.class);
    Mockito.when(span.getTraceId()).thenReturn(DDTraceId.from(traceId));

    boolean sampled = sampler.sample(span);

    assertEquals(expected, sampled);
  }

  static Stream<Arguments> samplingNoneArguments() {
    return Stream.of(
        Arguments.of(false, "10428415896243638596"),
        Arguments.of(false, "11199607447739267382"),
        Arguments.of(false, "11273630029763932141"),
        Arguments.of(false, "11407674492757219439"),
        Arguments.of(false, "11792151447964398879"),
        Arguments.of(false, "12432680895096110463"),
        Arguments.of(false, "13126262220165910460"),
        Arguments.of(false, "13174268766980400525"),
        Arguments.of(false, "15505210698284655633"),
        Arguments.of(false, "15649472107743074779"),
        Arguments.of(false, "17204678798284737396"),
        Arguments.of(false, "17344948852394588913"),
        Arguments.of(false, "17496662575514578077"),
        Arguments.of(false, "18252401681137062077"),
        Arguments.of(false, "18317291550776694829"),
        Arguments.of(false, "1874068156324778273"),
        Arguments.of(false, "1905388747193831650"),
        Arguments.of(false, "2202916659517317514"),
        Arguments.of(false, "2227583514184312746"),
        Arguments.of(false, "2338498362660772719"),
        Arguments.of(false, "2781055864473387780"),
        Arguments.of(false, "3328451335138149956"),
        Arguments.of(false, "3337066551442961397"),
        Arguments.of(false, "3409814636252858217"),
        Arguments.of(false, "3510942875414458836"),
        Arguments.of(false, "3784560248718450071"),
        Arguments.of(false, "4751997750760398084"),
        Arguments.of(false, "4831389563158288344"),
        Arguments.of(false, "4990765271833742716"),
        Arguments.of(false, "5089134323978233018"),
        Arguments.of(false, "5199948958991797301"),
        Arguments.of(false, "5577006791947779410"),
        Arguments.of(false, "5600924393587988459"),
        Arguments.of(false, "5793183108815074904"),
        Arguments.of(false, "6263450610539110790"),
        Arguments.of(false, "6382800227808658932"),
        Arguments.of(false, "6651414131918424343"),
        Arguments.of(false, "6842348953158377901"),
        Arguments.of(false, "6941261091797652072"),
        Arguments.of(false, "7273596521315663110"),
        Arguments.of(false, "7504504064263669287"),
        Arguments.of(false, "788787457839692041"),
        Arguments.of(false, "7955079406183515637"),
        Arguments.of(false, "8549944162621642512"),
        Arguments.of(false, "8603989663476771718"),
        Arguments.of(false, "8807817071862113702"),
        Arguments.of(false, "9010467728050264449"),
        Arguments.of(false, "10667007354186551956"),
        Arguments.of(false, "10683692646452562431"),
        Arguments.of(false, "10821471013040158923"),
        Arguments.of(false, "10950412492527322440"),
        Arguments.of(false, "11239168150708129139"),
        Arguments.of(false, "1169089424364679180"),
        Arguments.of(false, "11818186001859264308"),
        Arguments.of(false, "11833901312327420776"),
        Arguments.of(false, "11926759511765359899"),
        Arguments.of(false, "11926873763676642186"),
        Arguments.of(false, "11963748953446345529"),
        Arguments.of(false, "11998794077335055257"),
        Arguments.of(false, "12096659438561119542"),
        Arguments.of(false, "12156940908066221323"),
        Arguments.of(false, "12947799971452915849"),
        Arguments.of(false, "13260572831089785859"),
        Arguments.of(false, "13771804148684671731"),
        Arguments.of(false, "14117161486975057715"),
        Arguments.of(false, "14242321332569825828"),
        Arguments.of(false, "14486903973548550719"),
        Arguments.of(false, "14967026985784794439"),
        Arguments.of(false, "15213854965919594827"),
        Arguments.of(false, "15352856648520921629"),
        Arguments.of(false, "15399114114227588261"),
        Arguments.of(false, "15595235597337683065"),
        Arguments.of(false, "16194613440650274502"),
        Arguments.of(false, "1687184559264975024"),
        Arguments.of(false, "17490665426807838719"),
        Arguments.of(false, "18218388313430417611"),
        Arguments.of(false, "2601737961087659062"),
        Arguments.of(false, "261049867304784443"),
        Arguments.of(false, "2740103009342231109"),
        Arguments.of(false, "2970700287221458280"),
        Arguments.of(false, "3916589616287113937"),
        Arguments.of(false, "4324745483838182873"),
        Arguments.of(false, "4937104021912138218"),
        Arguments.of(false, "5486140987150761883"),
        Arguments.of(false, "5944830206637008055"),
        Arguments.of(false, "6296367092202729479"),
        Arguments.of(false, "6334824724549167320"),
        Arguments.of(false, "6556961545928831643"),
        Arguments.of(false, "6735196588112087610"),
        Arguments.of(false, "7388428680384065704"),
        Arguments.of(false, "8249030965139585917"),
        Arguments.of(false, "837825985403119657"),
        Arguments.of(false, "8505906760983331750"),
        Arguments.of(false, "8674665223082153551"),
        Arguments.of(false, "894385949183117216"),
        Arguments.of(false, "898860202204764712"),
        Arguments.of(false, "9768663798983814715"),
        Arguments.of(false, "9828766684487745566"),
        Arguments.of(false, "9908585559158765387"),
        Arguments.of(false, "9956202364908137547"));
  }

  @ParameterizedTest
  @MethodSource("samplingAllArguments")
  void testSamplingAll(boolean expected, String traceId) {
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(1);
    DDSpan span = Mockito.mock(DDSpan.class);
    Mockito.when(span.getTraceId()).thenReturn(DDTraceId.from(traceId));

    boolean sampled = sampler.sample(span);

    assertEquals(expected, sampled);
  }

  static Stream<Arguments> samplingAllArguments() {
    return Stream.of(
        Arguments.of(true, "10428415896243638596"),
        Arguments.of(true, "11199607447739267382"),
        Arguments.of(true, "11273630029763932141"),
        Arguments.of(true, "11407674492757219439"),
        Arguments.of(true, "11792151447964398879"),
        Arguments.of(true, "12432680895096110463"),
        Arguments.of(true, "13126262220165910460"),
        Arguments.of(true, "13174268766980400525"),
        Arguments.of(true, "15505210698284655633"),
        Arguments.of(true, "15649472107743074779"),
        Arguments.of(true, "17204678798284737396"),
        Arguments.of(true, "17344948852394588913"),
        Arguments.of(true, "17496662575514578077"),
        Arguments.of(true, "18252401681137062077"),
        Arguments.of(true, "18317291550776694829"),
        Arguments.of(true, "1874068156324778273"),
        Arguments.of(true, "1905388747193831650"),
        Arguments.of(true, "2202916659517317514"),
        Arguments.of(true, "2227583514184312746"),
        Arguments.of(true, "2338498362660772719"),
        Arguments.of(true, "2781055864473387780"),
        Arguments.of(true, "3328451335138149956"),
        Arguments.of(true, "3337066551442961397"),
        Arguments.of(true, "3409814636252858217"),
        Arguments.of(true, "3510942875414458836"),
        Arguments.of(true, "3784560248718450071"),
        Arguments.of(true, "4751997750760398084"),
        Arguments.of(true, "4831389563158288344"),
        Arguments.of(true, "4990765271833742716"),
        Arguments.of(true, "5089134323978233018"),
        Arguments.of(true, "5199948958991797301"),
        Arguments.of(true, "5577006791947779410"),
        Arguments.of(true, "5600924393587988459"),
        Arguments.of(true, "5793183108815074904"),
        Arguments.of(true, "6263450610539110790"),
        Arguments.of(true, "6382800227808658932"),
        Arguments.of(true, "6651414131918424343"),
        Arguments.of(true, "6842348953158377901"),
        Arguments.of(true, "6941261091797652072"),
        Arguments.of(true, "7273596521315663110"),
        Arguments.of(true, "7504504064263669287"),
        Arguments.of(true, "788787457839692041"),
        Arguments.of(true, "7955079406183515637"),
        Arguments.of(true, "8549944162621642512"),
        Arguments.of(true, "8603989663476771718"),
        Arguments.of(true, "8807817071862113702"),
        Arguments.of(true, "9010467728050264449"),
        Arguments.of(true, "10667007354186551956"),
        Arguments.of(true, "10683692646452562431"),
        Arguments.of(true, "10821471013040158923"),
        Arguments.of(true, "10950412492527322440"),
        Arguments.of(true, "11239168150708129139"),
        Arguments.of(true, "1169089424364679180"),
        Arguments.of(true, "11818186001859264308"),
        Arguments.of(true, "11833901312327420776"),
        Arguments.of(true, "11926759511765359899"),
        Arguments.of(true, "11926873763676642186"),
        Arguments.of(true, "11963748953446345529"),
        Arguments.of(true, "11998794077335055257"),
        Arguments.of(true, "12096659438561119542"),
        Arguments.of(true, "12156940908066221323"),
        Arguments.of(true, "12947799971452915849"),
        Arguments.of(true, "13260572831089785859"),
        Arguments.of(true, "13771804148684671731"),
        Arguments.of(true, "14117161486975057715"),
        Arguments.of(true, "14242321332569825828"),
        Arguments.of(true, "14486903973548550719"),
        Arguments.of(true, "14967026985784794439"),
        Arguments.of(true, "15213854965919594827"),
        Arguments.of(true, "15352856648520921629"),
        Arguments.of(true, "15399114114227588261"),
        Arguments.of(true, "15595235597337683065"),
        Arguments.of(true, "16194613440650274502"),
        Arguments.of(true, "1687184559264975024"),
        Arguments.of(true, "17490665426807838719"),
        Arguments.of(true, "18218388313430417611"),
        Arguments.of(true, "2601737961087659062"),
        Arguments.of(true, "261049867304784443"),
        Arguments.of(true, "2740103009342231109"),
        Arguments.of(true, "2970700287221458280"),
        Arguments.of(true, "3916589616287113937"),
        Arguments.of(true, "4324745483838182873"),
        Arguments.of(true, "4937104021912138218"),
        Arguments.of(true, "5486140987150761883"),
        Arguments.of(true, "5944830206637008055"),
        Arguments.of(true, "6296367092202729479"),
        Arguments.of(true, "6334824724549167320"),
        Arguments.of(true, "6556961545928831643"),
        Arguments.of(true, "6735196588112087610"),
        Arguments.of(true, "7388428680384065704"),
        Arguments.of(true, "8249030965139585917"),
        Arguments.of(true, "837825985403119657"),
        Arguments.of(true, "8505906760983331750"),
        Arguments.of(true, "8674665223082153551"),
        Arguments.of(true, "894385949183117216"),
        Arguments.of(true, "898860202204764712"),
        Arguments.of(true, "9768663798983814715"),
        Arguments.of(true, "9828766684487745566"),
        Arguments.of(true, "9908585559158765387"),
        Arguments.of(true, "9956202364908137547"));
  }

  static final BigDecimal CUTOFF_FACTOR =
      new BigDecimal(BigInteger.valueOf(2).pow(64).subtract(BigInteger.ONE));

  @ParameterizedTest
  @MethodSource("cutoffRatesArguments")
  void testCutoffCalculation(int rate) {
    long cutoff = DeterministicSampler.cutoff(rate / 100.0);
    long expected =
        new BigDecimal(rate / 100D).multiply(CUTOFF_FACTOR).toBigInteger().longValue()
            + Long.MIN_VALUE;
    assertTrue(Math.abs(cutoff - expected) <= 1);
  }

  static Stream<Arguments> cutoffRatesArguments() {
    return IntStream.rangeClosed(0, 100).mapToObj(Arguments::of);
  }
}
