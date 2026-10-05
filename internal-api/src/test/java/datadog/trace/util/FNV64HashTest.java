package datadog.trace.util;

import static datadog.trace.util.FNV64Hash.continueHash;
import static datadog.trace.util.FNV64Hash.generateHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.util.FNV64Hash.Version;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

// Test cases from http://www.isthe.com/chongo/src/fnv/test_fnv.c
class FNV64HashTest {

  @ParameterizedTest(name = "{index}: {0}")
  @TableTest({
    "Scenario                                                                  | input                                                                     | version1         | version1a       ",
    "empty string                                                              | ''                                                                        | cbf29ce484222325 | cbf29ce484222325",
    "a                                                                         | a                                                                         | af63bd4c8601b7be | af63dc4c8601ec8c",
    "b                                                                         | b                                                                         | af63bd4c8601b7bd | af63df4c8601f1a5",
    "c                                                                         | c                                                                         | af63bd4c8601b7bc | af63de4c8601eff2",
    "d                                                                         | d                                                                         | af63bd4c8601b7bb | af63d94c8601e773",
    "e                                                                         | e                                                                         | af63bd4c8601b7ba | af63d84c8601e5c0",
    "f                                                                         | f                                                                         | af63bd4c8601b7b9 | af63db4c8601ead9",
    "fo                                                                        | fo                                                                        | 08326207b4eb2f34 | 08985907b541d342",
    "foo                                                                       | foo                                                                       | d8cbc7186ba13533 | dcb27518fed9d577",
    "foob                                                                      | foob                                                                      | 0378817ee2ed65cb | dd120e790c2512af",
    "fooba                                                                     | fooba                                                                     | d329d59b9963f790 | cac165afa2fef40a",
    "foobar                                                                    | foobar                                                                    | 340d8765a4dda9c2 | 85944171f73967e8",
    "ch                                                                        | ch                                                                        | 08326507b4eb341c | 08a25607b54a22ae",
    "cho                                                                       | cho                                                                       | d8d5c8186ba98bfb | f5faf0190cf90df3",
    "chon                                                                      | chon                                                                      | 1ccefc7ef118dbef | f27397910b3221c7",
    "chong                                                                     | chong                                                                     | 0c92fab3ad3db77a | 2c8c2b76062f22e0",
    "chongo                                                                    | chongo                                                                    | 9b77794f5fdec421 | e150688c8217b8fd",
    "chongo (trailing space)                                                   | 'chongo '                                                                 | 0ac742dfe7874433 | f35a83c10e4f1f87",
    "chongo w                                                                  | chongo w                                                                  | d7dad5766ad8e2de | d1edd10b507344d0",
    "chongo wa                                                                 | chongo wa                                                                 | a1bb96378e897f5b | 2a5ee739b3ddb8c3",
    "chongo was                                                                | chongo was                                                                | 5b3f9b6733a367d2 | dcfb970ca1c0d310",
    "chongo was (trailing space)                                               | 'chongo was '                                                             | b07ce25cbea969f6 | 4054da76daa6da90",
    "chongo was h                                                              | chongo was h                                                              | 8d9e9997f9df0d6a | f70a2ff589861368",
    "chongo was he                                                             | chongo was he                                                             | 838c673d9603cb7b | 4c628b38aed25f17",
    "chongo was her                                                            | chongo was her                                                            | 8b5ee8a5e872c273 | 9dd1f6510f78189f",
    "chongo was here                                                           | chongo was here                                                           | 4507c4e9fb00690c | a3de85bd491270ce",
    "chongo was here!                                                          | chongo was here!                                                          | 4c9ca59581b27f45 | 858e2fa32a55e61d",
    "cu                                                                        | cu                                                                        | 08326507b4eb3401 | 08a24307b54a0265",
    "cur                                                                       | cur                                                                       | d8d5ad186ba95dc1 | f5b9fd190cc18d15",
    "curd                                                                      | curd                                                                      | 1c72e17ef0ca4e97 | 4c968290ace35703",
    "curds                                                                     | curds                                                                     | 2183c1b327c38ae6 | 07174bd5c64d9350",
    "curds (trailing space)                                                    | 'curds '                                                                  | b66d096c914504f2 | 5a294c3ff5d18750",
    "curds a                                                                   | curds a                                                                   | 404bf57ad8476757 | 05b3c1aeb308b843",
    "curds an                                                                  | curds an                                                                  | 887976bd815498bb | b92a48da37d0f477",
    "curds and                                                                 | curds and                                                                 | 3afd7f02c2bf85a5 | 73cdddccd80ebc49",
    "curds and (trailing space)                                                | 'curds and '                                                              | fc4476b0eb70177f | d58c4c13210a266b",
    "curds and w                                                               | curds and w                                                               | 186d2da00f77ecba | e78b6081243ec194",
    "curds and wh                                                              | curds and wh                                                              | f97140fa48c74066 | b096f77096a39f34",
    "curds and whe                                                             | curds and whe                                                             | a2b1cf49aa926d37 | b425c54ff807b6a3",
    "curds and whey                                                            | curds and whey                                                            | 0690712cd6cf940c | 23e520e2751bb46e",
    "hi                                                                        | hi                                                                        | 08326007b4eb2b9c | 08ba5f07b55ec3da",
    "hello                                                                     | hello                                                                     | 7b495389bdbdd4c7 | a430d84680aabd0b",
    "127.0.0.1                                                                 | 127.0.0.1                                                                 | 34ad3b1041204318 | aabafe7104d914be",
    "127.0.0.2                                                                 | 127.0.0.2                                                                 | 34ad3b104120431b | aabafd7104d9130b",
    "127.0.0.3                                                                 | 127.0.0.3                                                                 | 34ad3b104120431a | aabafc7104d91158",
    "64.81.78.68                                                               | 64.81.78.68                                                               | 02a17ebca4aa3497 | e729bac5d2a8d3a7",
    "64.81.78.74                                                               | 64.81.78.74                                                               | 02a17dbca4aa32c8 | e72630c5d2a5b352",
    "64.81.78.84                                                               | 64.81.78.84                                                               | 02a184bca4aa3ed5 | e73042c5d2ae266d",
    "feedface                                                                  | feedface                                                                  | 5c2c346706186f36 | 0a83c86fee952abc",
    "feedfacedaffdeed                                                          | feedfacedaffdeed                                                          | ed9478212b267395 | 3e66d3d56b8caca1",
    "feedfacedeadbeef                                                          | feedfacedeadbeef                                                          | 8c54f0203249438a | cac54572bb1a6fc8",
    "http://antwrp.gsfc.nasa.gov/apod/astropix.html                            | http://antwrp.gsfc.nasa.gov/apod/astropix.html                            | cb27f4b8e1b6cc20 | 6be396289ce8a6da",
    "http://en.wikipedia.org/wiki/Fowler_Noll_Vo_hash                          | http://en.wikipedia.org/wiki/Fowler_Noll_Vo_hash                          | 26caf88bcbef2d19 | d9b957fb7fe794c5",
    "http://epod.usra.edu/                                                     | http://epod.usra.edu/                                                     | 8e6e063b97e61b8f | 05be33da04560a93",
    "http://exoplanet.eu/                                                      | http://exoplanet.eu/                                                      | b42750f7f3b7c37e | 0957f1577ba9747c",
    "http://hvo.wr.usgs.gov/cam3/                                              | http://hvo.wr.usgs.gov/cam3/                                              | f3c6ba64cf7ca99b | da2cc3acc24fba57",
    "http://hvo.wr.usgs.gov/cams/HMcam/                                        | http://hvo.wr.usgs.gov/cams/HMcam/                                        | ebfb69b427ea80fe | 74136f185b29e7f0",
    "http://hvo.wr.usgs.gov/kilauea/update/deformation.html                    | http://hvo.wr.usgs.gov/kilauea/update/deformation.html                    | 39b50c3ed970f46c | b2f2b4590edb93b2",
    "http://hvo.wr.usgs.gov/kilauea/update/images.html                         | http://hvo.wr.usgs.gov/kilauea/update/images.html                         | 5b9b177aa3eb3e8a | b3608fce8b86ae04",
    "http://hvo.wr.usgs.gov/kilauea/update/maps.html                           | http://hvo.wr.usgs.gov/kilauea/update/maps.html                           | 6510063ecf4ec903 | 4a3a865079359063",
    "http://hvo.wr.usgs.gov/volcanowatch/current_issue.html                    | http://hvo.wr.usgs.gov/volcanowatch/current_issue.html                    | 2b3bbd2c00797c7a | 5b3a7ef496880a50",
    "http://neo.jpl.nasa.gov/risk/                                             | http://neo.jpl.nasa.gov/risk/                                             | f1d6204ff5cb4aa7 | 48fae3163854c23b",
    "http://norvig.com/21-days.html                                            | http://norvig.com/21-days.html                                            | 4836e27ccf099f38 | 07aaa640476e0b9a",
    "http://primes.utm.edu/curios/home.php                                     | http://primes.utm.edu/curios/home.php                                     | 82efbb0dd073b44d | 2f653656383a687d",
    "http://slashdot.org/                                                      | http://slashdot.org/                                                      | 4a80c282ffd7d4c6 | a1031f8e7599d79c",
    "http://tux.wr.usgs.gov/Maps/155.25-19.5.html                              | http://tux.wr.usgs.gov/Maps/155.25-19.5.html                              | 305d1a9c9ee43bdf | a31908178ff92477",
    "http://volcano.wr.usgs.gov/kilaueastatus.php                              | http://volcano.wr.usgs.gov/kilaueastatus.php                              | 15c366948ffc6997 | 097edf3c14c3fb83",
    "http://www.avo.alaska.edu/activity/Redoubt.php                            | http://www.avo.alaska.edu/activity/Redoubt.php                            | 80153ae218916e7b | b51ca83feaa0971b",
    "http://www.dilbert.com/fast/                                              | http://www.dilbert.com/fast/                                              | fa23e2bdf9e2a9e1 | dd3c0d96d784f2e9",
    "http://www.fourmilab.ch/gravitation/orbits/                               | http://www.fourmilab.ch/gravitation/orbits/                               | d47e8d8a2333c6de | 86cd26a9ea767d78",
    "http://www.fpoa.net/                                                      | http://www.fpoa.net/                                                      | 7e128095f688b056 | e6b215ff54a30c18",
    "http://www.ioccc.org/index.html                                           | http://www.ioccc.org/index.html                                           | 2f5356890efcedab | ec5b06a1c5531093",
    "http://www.isthe.com/cgi-bin/number.cgi                                   | http://www.isthe.com/cgi-bin/number.cgi                                   | 95c2b383014f55c5 | 45665a929f9ec5e5",
    "http://www.isthe.com/chongo/bio.html                                      | http://www.isthe.com/chongo/bio.html                                      | 4727a5339ce6070f | 8c7609b4a9f10907",
    "http://www.isthe.com/chongo/index.html                                    | http://www.isthe.com/chongo/index.html                                    | b0555ecd575108e9 | 89aac3a491f0d729",
    "http://www.isthe.com/chongo/src/calc/lucas-calc                           | http://www.isthe.com/chongo/src/calc/lucas-calc                           | 48d785770bb4af37 | 32ce6b26e0f4a403",
    "http://www.isthe.com/chongo/tech/astro/venus2004.html                     | http://www.isthe.com/chongo/tech/astro/venus2004.html                     | 09d4701c12af02b1 | 614ab44e02b53e01",
    "http://www.isthe.com/chongo/tech/astro/vita.html                          | http://www.isthe.com/chongo/tech/astro/vita.html                          | 79f031e78f3cf62e | fa6472eb6eef3290",
    "http://www.isthe.com/chongo/tech/comp/c/expert.html                       | http://www.isthe.com/chongo/tech/comp/c/expert.html                       | 52a1ee85db1b5a94 | 9e5d75eb1948eb6a",
    "http://www.isthe.com/chongo/tech/comp/calc/index.html                     | http://www.isthe.com/chongo/tech/comp/calc/index.html                     | 6bd95b2eb37fa6b8 | b6d12ad4a8671852",
    "http://www.isthe.com/chongo/tech/comp/fnv/index.html                      | http://www.isthe.com/chongo/tech/comp/fnv/index.html                      | 74971b7077aef85d | 88826f56eba07af1",
    "http://www.isthe.com/chongo/tech/math/number/howhigh.html                 | http://www.isthe.com/chongo/tech/math/number/howhigh.html                 | b4e4fae2ffcc1aad | 44535bf2645bc0fd",
    "http://www.isthe.com/chongo/tech/math/number/number.html                  | http://www.isthe.com/chongo/tech/math/number/number.html                  | 2bd48bd898b8f63a | 169388ffc21e3728",
    "http://www.isthe.com/chongo/tech/math/prime/mersenne.html                 | http://www.isthe.com/chongo/tech/math/prime/mersenne.html                 | e9966ac1556257f6 | f68aac9e396d8224",
    "http://www.isthe.com/chongo/tech/math/prime/mersenne.html#largest         | http://www.isthe.com/chongo/tech/math/prime/mersenne.html#largest         | 92a3d1cd078ba293 | 8e87d7e7472b3883",
    "http://www.lavarnd.org/cgi-bin/corpspeak.cgi                              | http://www.lavarnd.org/cgi-bin/corpspeak.cgi                              | f81175a482e20ab8 | 295c26caa8b423de",
    "http://www.lavarnd.org/cgi-bin/haiku.cgi                                  | http://www.lavarnd.org/cgi-bin/haiku.cgi                                  | 5bbb3de722e73048 | 322c814292e72176",
    "http://www.lavarnd.org/cgi-bin/rand-none.cgi                              | http://www.lavarnd.org/cgi-bin/rand-none.cgi                              | 6b4f363492b9f2be | 8a06550eb8af7268",
    "http://www.lavarnd.org/cgi-bin/randdist.cgi                               | http://www.lavarnd.org/cgi-bin/randdist.cgi                               | c2d559df73d59875 | ef86d60e661bcf71",
    "http://www.lavarnd.org/index.html                                         | http://www.lavarnd.org/index.html                                         | f75f62284bc7a8c2 | 9e5426c87f30ee54",
    "http://www.lavarnd.org/what/nist-test.html                                | http://www.lavarnd.org/what/nist-test.html                                | da8dd8e116a9f1cc | f1ea8aa826fd047e",
    "http://www.macosxhints.com/                                               | http://www.macosxhints.com/                                               | bdc1e6ab76057885 | 0babaf9a642cb769",
    "http://www.mellis.com/                                                    | http://www.mellis.com/                                                    | fec6a4238a1224a0 | 4b3341d4068d012e",
    "http://www.nature.nps.gov/air/webcams/parks/havoso2alert/havoalert.cfm    | http://www.nature.nps.gov/air/webcams/parks/havoso2alert/havoalert.cfm    | c03f40f3223e290e | d15605cbc30a335c",
    "http://www.nature.nps.gov/air/webcams/parks/havoso2alert/timelines_24.cfm | http://www.nature.nps.gov/air/webcams/parks/havoso2alert/timelines_24.cfm | 1ed21673466ffda9 | 5b21060aed8412e5",
    "http://www.paulnoll.com/                                                  | http://www.paulnoll.com/                                                  | df70f906bb0dd2af | 45e2cda1ce6f4227",
    "http://www.pepysdiary.com/                                                | http://www.pepysdiary.com/                                                | f3dcda369f2af666 | 50ae3745033ad7d4",
    "http://www.sciencenews.org/index/home/activity/view                       | http://www.sciencenews.org/index/home/activity/view                       | 9ebb11573cdcebde | aa4588ced46bf414",
    "http://www.skyandtelescope.com/                                           | http://www.skyandtelescope.com/                                           | 81c72d9077fedca0 | c1b0056c4a95467e",
    "http://www.sput.nl/~rob/sirius.html                                       | http://www.sput.nl/~rob/sirius.html                                       | 0ec074a31be5fb15 | 56576a71de8b4089",
    "http://www.systemexperts.com/                                             | http://www.systemexperts.com/                                             | 2a8b3280b6c48f20 | bf20965fa6dc927e",
    "http://www.tq-international.com/phpBB3/index.php                          | http://www.tq-international.com/phpBB3/index.php                          | fd31777513309344 | 569f8383c2040882",
    "http://www.travelquesttours.com/index.htm                                 | http://www.travelquesttours.com/index.htm                                 | 194534a86ad006b6 | e1e772fba08feca0",
    "http://www.wunderground.com/global/stations/89606.html                    | http://www.wunderground.com/global/stations/89606.html                    | 3be6fdf46e0cfe12 | 4ced94af97138ac4"
  })
  @MethodSource("stringTestCasesSpecialArguments")
  void stringTestCases(String input, String version1, String version1a) {
    long outputV1 = generateHash(input, Version.v1);
    long outputV1a = generateHash(input, Version.v1A);

    assertEquals(Long.parseUnsignedLong(version1, 16), outputV1);
    assertEquals(Long.parseUnsignedLong(version1a, 16), outputV1a);
  }

  private static Stream<Arguments> stringTestCasesSpecialArguments() {
    return Stream.of(
        arguments("chongo was here!\n", "e0aca20b624e4235", "46810940eff5f915"),
        arguments("curds and whey\n", "f7045b3102b8906e", "1a0b44ccfe1385ec"),
        arguments("line 1\nline 2\nline 3", "a64e5f36c9e2b0e3", "7829851fac17b143"),
        arguments("chongo <Landon Curt Noll> /\\../\\", "8fd0680da3088a04", "2c8f4c9af81bcf06"),
        arguments("chongo (Landon Curt Noll) /\\../\\", "b37d55d81c57b331", "3605a2ac253d2db1"),
        arguments(repeat("21701", 10), "017cc137a07eb057", "c4112ffb337a82fb"),
        arguments(repeat("M21701", 10), "9428fc6e7d26b54d", "d64a4fd41de38b7d"),
        arguments(repeat("2^21701-1", 10), "9aaa2e3603ef8ad7", "4cfc32329edebcbb"),
        arguments(repeat("23209", 10), "705f8189dbb58299", "694bc4e54cc315f9"),
        arguments(repeat("M23209", 10), "415a7f554391ca69", "a3d7cb273b011721"),
        arguments(repeat("2^23209-1", 10), "cfe3d49fa2bdc555", "577c2f8b6115bfa5"),
        arguments(repeat("391581216093", 10), "43c94e2c8b277509", "33b96c3cd65b5f71"),
        arguments(repeat("391581*2^216093-1", 10), "3cbfd4e4ea670359", "d845097780602bb9"),
        arguments(repeat("FEDCBA9876543210", 10), "14468ff93ac22dc5", "83544f33b58773a5"),
        arguments(repeat("EFCDAB8967452301", 10), "6d99f6df321ca5d5", "c71b3bc175e72bc5"),
        arguments(repeat("0123456789ABCDEF", 10), "ef1b2a2c86831d35", "b6ef0e6950f52ed5"),
        arguments(repeat("1032547698BADCFE", 10), "55248ce88f45f035", "922908fe9a861ba5"),
        arguments(repeat("~", 500), "15e96e1613df98b5", "c1af12bdfe16b5b5"));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("continuationTestCasesArguments")
  void continuationTestCases(String input1, String input2, String version1, String version1a) {
    long temp = generateHash(input1, Version.v1);
    long outputV1 = continueHash(temp, input2, Version.v1);

    temp = generateHash(input1, Version.v1A);
    long outputV1a = continueHash(temp, input2, Version.v1A);

    assertEquals(Long.parseUnsignedLong(version1, 16), outputV1);
    assertEquals(Long.parseUnsignedLong(version1a, 16), outputV1a);
  }

  private static Stream<Arguments> continuationTestCasesArguments() {
    return Stream.of(
        arguments("", "", "cbf29ce484222325", "cbf29ce484222325"),
        arguments("chongo", " was here!\n", "e0aca20b624e4235", "46810940eff5f915"),
        arguments("chongo was here!\n", "", "e0aca20b624e4235", "46810940eff5f915"),
        arguments("", "chongo was here!\n", "e0aca20b624e4235", "46810940eff5f915"),
        arguments(repeat("M21701", 5), repeat("M21701", 5), "9428fc6e7d26b54d", "d64a4fd41de38b7d"),
        arguments(repeat("M21701", 2), repeat("M21701", 8), "9428fc6e7d26b54d", "d64a4fd41de38b7d"),
        arguments(
            repeat("M21701", 7), repeat("M21701", 3), "9428fc6e7d26b54d", "d64a4fd41de38b7d"));
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("binaryTestCasesArguments")
  void binaryTestCases(byte[] input, String version1, String version1a) {
    long outputV1 = generateHash(input, Version.v1);
    long outputV1a = generateHash(input, Version.v1A);

    assertEquals(Long.parseUnsignedLong(version1, 16), outputV1);
    assertEquals(Long.parseUnsignedLong(version1a, 16), outputV1a);
  }

  private static Stream<Arguments> binaryTestCasesArguments() {
    return Stream.of(
        arguments(bytes(0xff, 0x00, 0x00, 0x01), "d6b2b17bf4b71261", "6961196491cc682d"),
        arguments(bytes(0x01, 0x00, 0x00, 0xff), "447bfb7f98e615b5", "ad2bb1774799dfe9"),
        arguments(bytes(0xff, 0x00, 0x00, 0x02), "d6b2b17bf4b71262", "6961166491cc6314"),
        arguments(bytes(0x02, 0x00, 0x00, 0xff), "3bd2807f93fe1660", "8d1bb3904a3b1236"),
        arguments(bytes(0xff, 0x00, 0x00, 0x03), "d6b2b17bf4b71263", "6961176491cc64c7"),
        arguments(bytes(0x03, 0x00, 0x00, 0xff), "3329057f8f16170b", "ed205d87f40434c7"),
        arguments(bytes(0xff, 0x00, 0x00, 0x04), "d6b2b17bf4b71264", "6961146491cc5fae"),
        arguments(bytes(0x04, 0x00, 0x00, 0xff), "2a7f8a7f8a2e19b6", "cd3baf5e44f8ad9c"),
        arguments(bytes(0x40, 0x51, 0x4e, 0x44), "23d3767e64b2f98a", "e3b36596127cd6d8"),
        arguments(bytes(0x44, 0x4e, 0x51, 0x40), "ff768d7e4f9d86a4", "f77f1072c8e8a646"),
        arguments(bytes(0x40, 0x51, 0x4e, 0x4a), "23d3767e64b2f984", "e3b36396127cd372"),
        arguments(bytes(0x4a, 0x4e, 0x51, 0x40), "ccd1837e334e4aa6", "6067dce9932ad458"),
        arguments(bytes(0x40, 0x51, 0x4e, 0x54), "23d3767e64b2f99a", "e3b37596127cf208"),
        arguments(bytes(0x54, 0x4e, 0x51, 0x40), "7691fd7e028f6754", "4b7b10fa9fe83936"),
        arguments(repeat(bytes(0x54, 0xc5), 10), "82c6d3f3a0ccdf7d", "0803564445050395"),
        arguments(repeat(bytes(0xc5, 0x54), 10), "c86eeea00cf09b65", "aa1574ecf4642ffd"),
        arguments(repeat(bytes(0x5a, 0xa9), 10), "f0f9c56039b25191", "b7ec8c1a769fb4c1"),
        arguments(repeat(bytes(0xa9, 0x5a), 10), "7075cb6abd1d32d9", "5d5cfce63359ab19"),
        arguments(
            repeat(bytes(0x05, 0xf9, 0x9d, 0x03, 0x4c, 0x81), 10),
            "c05887810f4d019d",
            "84d47645d02da3d5"),
        arguments(
            repeat(bytes(0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10), 10),
            "ebed699589d99c05",
            "9175cbb2160836c5"),
        arguments(
            repeat(bytes(0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23, 0x01), 10),
            "0cd410d08c36d625",
            "636806ac222ec985"),
        arguments(
            repeat(bytes(0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef), 10),
            "3b349c4d69ee5f05",
            "ead3d8a0f3dfdaa5"),
        arguments(
            repeat(bytes(0x10, 0x32, 0x54, 0x76, 0x98, 0xba, 0xdc, 0xfe), 10),
            "aa69ca6a18a4c885",
            "6d4821de275fd5c5"),
        arguments(repeat(bytes(0x00), 500), "1fe3fce62bd816b5", "1fe3fce62bd816b5"),
        arguments(repeat(bytes(0x07), 500), "0289a488a8df69d9", "c23e9fccd6f70591"),
        arguments(repeat(bytes(0x7f), 500), "e6be57375ad89b99", "39e9f18f2f85e221"));
  }

  private static String repeat(String value, int times) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < times; i++) {
      builder.append(value);
    }
    return builder.toString();
  }

  private static byte[] repeat(byte[] pattern, int times) {
    byte[] result = new byte[pattern.length * times];
    for (int i = 0; i < times; i++) {
      System.arraycopy(pattern, 0, result, i * pattern.length, pattern.length);
    }
    return result;
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = (byte) values[i];
    }
    return result;
  }
}
