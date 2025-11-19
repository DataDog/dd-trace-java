package datadog.trace.common.sampling

import datadog.trace.api.DDTraceId
import datadog.trace.core.DDSpan
import datadog.trace.test.util.DDSpecification

class DeterministicTraceSamplerTest extends DDSpecification {

  def "test known values: #traceId"() {
    given:
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(0.5)
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDTraceId.from(traceId)
    }

    when:
    def sampled = sampler.sample(span)

    then:
    sampled == expected

    where:
    expected | traceId
    false    | "10428415896243638596"
    false    | "11199607447739267382"
    false    | "11273630029763932141"
    false    | "11407674492757219439"
    false    | "11792151447964398879"
    false    | "12432680895096110463"
    false    | "13126262220165910460"
    false    | "13174268766980400525"
    false    | "15505210698284655633"
    false    | "15649472107743074779"
    false    | "17204678798284737396"
    false    | "17344948852394588913"
    false    | "17496662575514578077"
    false    | "18252401681137062077"
    false    | "18317291550776694829"
    false    | "1874068156324778273"
    false    | "1905388747193831650"
    false    | "2202916659517317514"
    false    | "2227583514184312746"
    false    | "2338498362660772719"
    false    | "2781055864473387780"
    false    | "3328451335138149956"
    false    | "3337066551442961397"
    false    | "3409814636252858217"
    false    | "3510942875414458836"
    false    | "3784560248718450071"
    false    | "4751997750760398084"
    false    | "4831389563158288344"
    false    | "4990765271833742716"
    false    | "5089134323978233018"
    false    | "5199948958991797301"
    false    | "5577006791947779410"
    false    | "5600924393587988459"
    false    | "5793183108815074904"
    false    | "6263450610539110790"
    false    | "6382800227808658932"
    false    | "6651414131918424343"
    false    | "6842348953158377901"
    false    | "6941261091797652072"
    false    | "7273596521315663110"
    false    | "7504504064263669287"
    false    | "788787457839692041"
    false    | "7955079406183515637"
    false    | "8549944162621642512"
    false    | "8603989663476771718"
    false    | "8807817071862113702"
    false    | "9010467728050264449"
    true     | "10667007354186551956"
    true     | "10683692646452562431"
    true     | "10821471013040158923"
    true     | "10950412492527322440"
    true     | "11239168150708129139"
    true     | "1169089424364679180"
    true     | "11818186001859264308"
    true     | "11833901312327420776"
    true     | "11926759511765359899"
    true     | "11926873763676642186"
    true     | "11963748953446345529"
    true     | "11998794077335055257"
    true     | "12096659438561119542"
    true     | "12156940908066221323"
    true     | "12947799971452915849"
    true     | "13260572831089785859"
    true     | "13771804148684671731"
    true     | "14117161486975057715"
    true     | "14242321332569825828"
    true     | "14486903973548550719"
    true     | "14967026985784794439"
    true     | "15213854965919594827"
    true     | "15352856648520921629"
    true     | "15399114114227588261"
    true     | "15595235597337683065"
    true     | "16194613440650274502"
    true     | "1687184559264975024"
    true     | "17490665426807838719"
    true     | "18218388313430417611"
    true     | "2601737961087659062"
    true     | "261049867304784443"
    true     | "2740103009342231109"
    true     | "2970700287221458280"
    true     | "3916589616287113937"
    true     | "4324745483838182873"
    true     | "4937104021912138218"
    true     | "5486140987150761883"
    true     | "5944830206637008055"
    true     | "6296367092202729479"
    true     | "6334824724549167320"
    true     | "6556961545928831643"
    true     | "6735196588112087610"
    true     | "7388428680384065704"
    true     | "8249030965139585917"
    true     | "837825985403119657"
    true     | "8505906760983331750"
    true     | "8674665223082153551"
    true     | "894385949183117216"
    true     | "898860202204764712"
    true     | "9768663798983814715"
    true     | "9828766684487745566"
    true     | "9908585559158765387"
    true     | "9956202364908137547"
    true     | "9223372036854775808"
  }

  def "test sampling none: #traceId"() {
    given:
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(0)
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDTraceId.from(traceId)
    }

    when:
    def sampled = sampler.sample(span)

    then:
    sampled == expected

    // These values are repeated from the "known values test"
    // It is an arbitrary subset of all possible traceIds
    where:
    expected | traceId
    false    | "10428415896243638596"
    false    | "11199607447739267382"
    false    | "11273630029763932141"
    false    | "11407674492757219439"
    false    | "11792151447964398879"
    false    | "12432680895096110463"
    false    | "13126262220165910460"
    false    | "13174268766980400525"
    false    | "15505210698284655633"
    false    | "15649472107743074779"
    false    | "17204678798284737396"
    false    | "17344948852394588913"
    false    | "17496662575514578077"
    false    | "18252401681137062077"
    false    | "18317291550776694829"
    false    | "1874068156324778273"
    false    | "1905388747193831650"
    false    | "2202916659517317514"
    false    | "2227583514184312746"
    false    | "2338498362660772719"
    false    | "2781055864473387780"
    false    | "3328451335138149956"
    false    | "3337066551442961397"
    false    | "3409814636252858217"
    false    | "3510942875414458836"
    false    | "3784560248718450071"
    false    | "4751997750760398084"
    false    | "4831389563158288344"
    false    | "4990765271833742716"
    false    | "5089134323978233018"
    false    | "5199948958991797301"
    false    | "5577006791947779410"
    false    | "5600924393587988459"
    false    | "5793183108815074904"
    false    | "6263450610539110790"
    false    | "6382800227808658932"
    false    | "6651414131918424343"
    false    | "6842348953158377901"
    false    | "6941261091797652072"
    false    | "7273596521315663110"
    false    | "7504504064263669287"
    false    | "788787457839692041"
    false    | "7955079406183515637"
    false    | "8549944162621642512"
    false    | "8603989663476771718"
    false    | "8807817071862113702"
    false    | "9010467728050264449"
    false    | "10667007354186551956"
    false    | "10683692646452562431"
    false    | "10821471013040158923"
    false    | "10950412492527322440"
    false    | "11239168150708129139"
    false    | "1169089424364679180"
    false    | "11818186001859264308"
    false    | "11833901312327420776"
    false    | "11926759511765359899"
    false    | "11926873763676642186"
    false    | "11963748953446345529"
    false    | "11998794077335055257"
    false    | "12096659438561119542"
    false    | "12156940908066221323"
    false    | "12947799971452915849"
    false    | "13260572831089785859"
    false    | "13771804148684671731"
    false    | "14117161486975057715"
    false    | "14242321332569825828"
    false    | "14486903973548550719"
    false    | "14967026985784794439"
    false    | "15213854965919594827"
    false    | "15352856648520921629"
    false    | "15399114114227588261"
    false    | "15595235597337683065"
    false    | "16194613440650274502"
    false    | "1687184559264975024"
    false    | "17490665426807838719"
    false    | "18218388313430417611"
    false    | "2601737961087659062"
    false    | "261049867304784443"
    false    | "2740103009342231109"
    false    | "2970700287221458280"
    false    | "3916589616287113937"
    false    | "4324745483838182873"
    false    | "4937104021912138218"
    false    | "5486140987150761883"
    false    | "5944830206637008055"
    false    | "6296367092202729479"
    false    | "6334824724549167320"
    false    | "6556961545928831643"
    false    | "6735196588112087610"
    false    | "7388428680384065704"
    false    | "8249030965139585917"
    false    | "837825985403119657"
    false    | "8505906760983331750"
    false    | "8674665223082153551"
    false    | "894385949183117216"
    false    | "898860202204764712"
    false    | "9768663798983814715"
    false    | "9828766684487745566"
    false    | "9908585559158765387"
    false    | "9956202364908137547"
  }

  def "test sampling all: #traceId"() {
    given:
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(1)
    DDSpan span = Mock(DDSpan) {
      getTraceId() >> DDTraceId.from(traceId)
    }

    when:
    def sampled = sampler.sample(span)

    then:
    sampled == expected

    // These values are repeated from the "known values test"
    // It is an arbitrary subset of all possible traceIds
    where:
    expected | traceId
    true     | "10428415896243638596"
    true     | "11199607447739267382"
    true     | "11273630029763932141"
    true     | "11407674492757219439"
    true     | "11792151447964398879"
    true     | "12432680895096110463"
    true     | "13126262220165910460"
    true     | "13174268766980400525"
    true     | "15505210698284655633"
    true     | "15649472107743074779"
    true     | "17204678798284737396"
    true     | "17344948852394588913"
    true     | "17496662575514578077"
    true     | "18252401681137062077"
    true     | "18317291550776694829"
    true     | "1874068156324778273"
    true     | "1905388747193831650"
    true     | "2202916659517317514"
    true     | "2227583514184312746"
    true     | "2338498362660772719"
    true     | "2781055864473387780"
    true     | "3328451335138149956"
    true     | "3337066551442961397"
    true     | "3409814636252858217"
    true     | "3510942875414458836"
    true     | "3784560248718450071"
    true     | "4751997750760398084"
    true     | "4831389563158288344"
    true     | "4990765271833742716"
    true     | "5089134323978233018"
    true     | "5199948958991797301"
    true     | "5577006791947779410"
    true     | "5600924393587988459"
    true     | "5793183108815074904"
    true     | "6263450610539110790"
    true     | "6382800227808658932"
    true     | "6651414131918424343"
    true     | "6842348953158377901"
    true     | "6941261091797652072"
    true     | "7273596521315663110"
    true     | "7504504064263669287"
    true     | "788787457839692041"
    true     | "7955079406183515637"
    true     | "8549944162621642512"
    true     | "8603989663476771718"
    true     | "8807817071862113702"
    true     | "9010467728050264449"
    true     | "10667007354186551956"
    true     | "10683692646452562431"
    true     | "10821471013040158923"
    true     | "10950412492527322440"
    true     | "11239168150708129139"
    true     | "1169089424364679180"
    true     | "11818186001859264308"
    true     | "11833901312327420776"
    true     | "11926759511765359899"
    true     | "11926873763676642186"
    true     | "11963748953446345529"
    true     | "11998794077335055257"
    true     | "12096659438561119542"
    true     | "12156940908066221323"
    true     | "12947799971452915849"
    true     | "13260572831089785859"
    true     | "13771804148684671731"
    true     | "14117161486975057715"
    true     | "14242321332569825828"
    true     | "14486903973548550719"
    true     | "14967026985784794439"
    true     | "15213854965919594827"
    true     | "15352856648520921629"
    true     | "15399114114227588261"
    true     | "15595235597337683065"
    true     | "16194613440650274502"
    true     | "1687184559264975024"
    true     | "17490665426807838719"
    true     | "18218388313430417611"
    true     | "2601737961087659062"
    true     | "261049867304784443"
    true     | "2740103009342231109"
    true     | "2970700287221458280"
    true     | "3916589616287113937"
    true     | "4324745483838182873"
    true     | "4937104021912138218"
    true     | "5486140987150761883"
    true     | "5944830206637008055"
    true     | "6296367092202729479"
    true     | "6334824724549167320"
    true     | "6556961545928831643"
    true     | "6735196588112087610"
    true     | "7388428680384065704"
    true     | "8249030965139585917"
    true     | "837825985403119657"
    true     | "8505906760983331750"
    true     | "8674665223082153551"
    true     | "894385949183117216"
    true     | "898860202204764712"
    true     | "9768663798983814715"
    true     | "9828766684487745566"
    true     | "9908585559158765387"
    true     | "9956202364908137547"
  }

  static final BigDecimal CUTOFF_FACTOR = new BigDecimal(BigInteger.valueOf(2).pow(64).subtract(BigInteger.ONE))

  def "test cutoff calculation"() {
    when:
    long cutoff = DeterministicSampler.cutoff(rate / 100F)
    then:
    Math.abs(cutoff - new BigDecimal(rate / 100D)
      .multiply(CUTOFF_FACTOR)
      .toBigInteger()
      .longValue() + Long.MIN_VALUE) <= 1

    where:
    rate << (0..100)
  }

  def "test getKnuthSampleRate formatting: #rate -> #expected"() {
    given:
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(rate)

    when:
    String formatted = sampler.getKnuthSampleRate()

    then:
    formatted == expected

    where:
    rate         | expected
    0.0          | "0"
    0.000001     | "0.000001"
    0.000010     | "0.00001"
    0.000100     | "0.0001"
    0.001000     | "0.001"
    0.010000     | "0.01"
    0.100000     | "0.1"
    0.123456     | "0.123456"
    0.123450     | "0.12345"
    0.123400     | "0.1234"
    0.123000     | "0.123"
    0.120000     | "0.12"
    0.100000     | "0.1"
    0.500000     | "0.5"
    0.999999     | "0.999999"
    1.0          | "1"
    1.5          | "1"  // Values > 1 are clamped to 1
    -0.5         | "0"  // Values < 0 are clamped to 0
  }

  def "test getKnuthSampleRate precision and rounding"() {
    given:
    // Test edge cases around rounding
    DeterministicSampler sampler1 = new DeterministicSampler.TraceSampler(0.1234564)  // Should round down
    DeterministicSampler sampler2 = new DeterministicSampler.TraceSampler(0.1234565)  // Should round up
    DeterministicSampler sampler3 = new DeterministicSampler.TraceSampler(0.9999995)  // Should round up to 1

    expect:
    sampler1.getKnuthSampleRate() == "0.123456"
    sampler2.getKnuthSampleRate() == "0.123457"
    sampler3.getKnuthSampleRate() == "1"
  }

  def "test getKnuthSampleRate is consistent"() {
    given:
    DeterministicSampler sampler = new DeterministicSampler.TraceSampler(0.123456)

    when:
    String rate1 = sampler.getKnuthSampleRate()
    String rate2 = sampler.getKnuthSampleRate()

    then:
    rate1 == rate2
    rate1 == "0.123456"
  }
}
