package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

public final class TagMapFuzzTest {
  static final int NUM_KEYS = 128;
  static final int MAX_NUM_ACTIONS = 32;
  static final int MIN_NUM_ACTIONS = 8;

  @Test
  void test() {
    test(generateTest());
  }

  @Test
  void testMerge() {
    TestCase mapACase = generateTest();
    TestCase mapBCase = generateTest();

    OptimizedTagMap tagMapA = test(mapACase);
    OptimizedTagMap tagMapB = test(mapBCase);

    HashMap<String, Object> hashMapA = new HashMap<>(tagMapA);
    HashMap<String, Object> hashMapB = new HashMap<>(tagMapB);

    tagMapA.putAll(tagMapB);
    hashMapA.putAll(hashMapB);

    assertMapEquals(hashMapA, tagMapA);
  }

  @Test
  void priorFailingCase0() {
    TagMap map =
        makeTagMap(
            remove("key-4"),
            put("key-71", "values-443049055"),
            put("key-2", "values-1227065898"),
            put("key-25", "values-696891692"),
            put("key-93", "values-763707175"),
            put("key-23", "values--1514091210"),
            put("key-16", "values--1388742686"));

    MapAction failingAction =
        putAllTagMap(
            "key-17",
            "values--2085338893",
            "key-51",
            "values-960243765",
            "key-33",
            "values-1493544499",
            "key-46",
            "values-697926849",
            "key-70",
            "values--184054454",
            "key-67",
            "values-374577326",
            "key-9",
            "values--742453833",
            "key-11",
            "values-1606950841",
            "key-119",
            "values--1914593057",
            "key-53",
            "values-375236438",
            "key-96",
            "values--107185569",
            "key-47",
            "values--1276407408",
            "key-125",
            "values--1627172151",
            "key-110",
            "values--1227150283",
            "key-15",
            "values-380379920",
            "key-42",
            "values--632271048",
            "key-99",
            "values--650090786",
            "key-8",
            "values--1990889145",
            "key-103",
            "values-1815698254",
            "key-120",
            "values-279025031",
            "key-93",
            "values-589795963",
            "key-12",
            "values--935895941",
            "key-105",
            "values-94976227",
            "key-85",
            "values--424609970",
            "key-78",
            "values-1231948102",
            "key-115",
            "values-88670282",
            "key-26",
            "values-733903384",
            "key-100",
            "values-2102967487",
            "key-74",
            "values-958598087",
            "key-104",
            "values-264458254",
            "key-125",
            "values--1781797927",
            "key-27",
            "values--562810078",
            "key-7",
            "values--376776745",
            "key-111",
            "values-263564677",
            "key-50",
            "values--859673100",
            "key-57",
            "values-1585057281",
            "key-48",
            "values--617889787",
            "key-98",
            "values--1878108220",
            "key-9",
            "values--227223375",
            "key-59",
            "values-1577082288",
            "key-94",
            "values--268049040",
            "key-0",
            "values-1708355496",
            "key-62",
            "values--733451297",
            "key-14",
            "values-232732747",
            "key-4",
            "values--406605642",
            "key-58",
            "values-1772476833",
            "key-8",
            "values--1155025225",
            "key-101",
            "values-144480545",
            "key-66",
            "values-355117269",
            "key-121",
            "values-1858008722",
            "key-33",
            "values-1947754079",
            "key-1",
            "values--1475603838",
            "key-125",
            "values--2146772243",
            "key-117",
            "values-852022714",
            "key-53",
            "values--2039348506",
            "key-65",
            "values-2011228657",
            "key-108",
            "values-1581592518",
            "key-17",
            "values-2129571020",
            "key-5",
            "values-1106900841",
            "key-80",
            "values-1791757923",
            "key-18",
            "values--1992962227",
            "key-2",
            "values-328863878",
            "key-110",
            "values-1182949334",
            "key-5",
            "values-1049403346",
            "key-107",
            "values-1246502060",
            "key-115",
            "values-2053931423",
            "key-19",
            "values--1731179633",
            "key-104",
            "values--1090790550",
            "key-67",
            "values--1312759979",
            "key-10",
            "values-1411135",
            "key-109",
            "values--1784920248",
            "key-20",
            "values--827644780",
            "key-55",
            "values--1610270998",
            "key-60",
            "values-1287959520",
            "key-31",
            "values-1686541667",
            "key-41",
            "values-399844058",
            "key-115",
            "values-2045201464",
            "key-78",
            "values-358081227",
            "key-57",
            "values--1374149269",
            "key-65",
            "values-1871734555",
            "key-124",
            "values--211494558",
            "key-119",
            "values-1757597102",
            "key-32",
            "values--336988038",
            "key-85",
            "values-1415155858",
            "key-44",
            "values-1455425178",
            "key-48",
            "values--325658059",
            "key-68",
            "values--793590840",
            "key-96",
            "values--2010766492",
            "key-40",
            "values-2007171160",
            "key-29",
            "values-186945230",
            "key-63",
            "values-1741962849",
            "key-26",
            "values-948582805",
            "key-31",
            "values-47004766",
            "key-90",
            "values-1304302008",
            "key-69",
            "values-2120328211",
            "key-111",
            "values-2053321468",
            "key-69",
            "values--498524858",
            "key-125",
            "values--193004619",
            "key-30",
            "values--1142090845",
            "key-15",
            "values--1334900170",
            "key-33",
            "values-1011001500",
            "key-55",
            "values-452401605",
            "key-18",
            "values-1260118555",
            "key-44",
            "values--1109396459",
            "key-2",
            "values--555647718",
            "key-61",
            "values-1060742038",
            "key-51",
            "values--827099230",
            "key-62",
            "values--1443716296",
            "key-16",
            "values-534556355",
            "key-81",
            "values--787910427",
            "key-20",
            "values-1429697120",
            "key-36",
            "values--1775988293",
            "key-66",
            "values-624669635",
            "key-25",
            "values--684183265",
            "key-26",
            "values-293626449",
            "key-91",
            "values--1212867803",
            "key-6",
            "values-1778251481",
            "key-83",
            "values-1257370908",
            "key-92",
            "values--1120490028",
            "key-111",
            "values-9646496",
            "key-90",
            "values-1485206899");
    failingAction.applyToTestMap(map);
    failingAction.verifyTestMap(map);
  }

  @Test
  void priorFailingCase1() {
    TagMap map = makeTagMap(put("key-68", "values--37178328"), put("key-93", "values--2093086281"));

    MapAction failingAction =
        putAllTagMap(
            "key-36",
            "values--1951535044",
            "key-59",
            "values--1045985660",
            "key-68",
            "values-1270827526",
            "key-65",
            "values-440073158",
            "key-91",
            "values-954365843",
            "key-75",
            "values-1014366449",
            "key-117",
            "values--1306617705",
            "key-90",
            "values-984567966",
            "key-120",
            "values--1802603599",
            "key-56",
            "values-319574488",
            "key-78",
            "values--711288173",
            "key-103",
            "values-694279462",
            "key-84",
            "values-1391260657",
            "key-59",
            "values--484807195",
            "key-67",
            "values-1675498322",
            "key-91",
            "values--227731796",
            "key-105",
            "values--1471022333",
            "key-112",
            "values--755617374",
            "key-117",
            "values--668324524",
            "key-65",
            "values-1165174761",
            "key-13",
            "values--1947081814",
            "key-72",
            "values-2032502631",
            "key-106",
            "values-256372025",
            "key-71",
            "values--995163162",
            "key-92",
            "values-972782926",
            "key-116",
            "values-25012447",
            "key-23",
            "values--979671053",
            "key-94",
            "values-367125724",
            "key-48",
            "values--2011523144",
            "key-14",
            "values-578926680",
            "key-65",
            "values-1325737627",
            "key-89",
            "values-1539092266",
            "key-100",
            "values--319629978",
            "key-53",
            "values-1125496255",
            "key-2",
            "values-1988036327",
            "key-105",
            "values--1333468536",
            "key-37",
            "values-351345678",
            "key-4",
            "values-683252782",
            "key-62",
            "values--1466612877",
            "key-100",
            "values-268100559",
            "key-104",
            "values-3517495",
            "key-48",
            "values--1588410835",
            "key-42",
            "values--180653405",
            "key-118",
            "values--1181647255",
            "key-17",
            "values-509279769",
            "key-33",
            "values-298668287",
            "key-76",
            "values-2062435628",
            "key-18",
            "values-287811864",
            "key-46",
            "values--1337930894",
            "key-50",
            "values-2089310564",
            "key-24",
            "values--1870293199",
            "key-47",
            "values--1155431370",
            "key-81",
            "values--1507929564",
            "key-115",
            "values-1149614815",
            "key-57",
            "values--334611395",
            "key-86",
            "values-146447703",
            "key-107",
            "values-938082683",
            "key-38",
            "values-338654203",
            "key-40",
            "values--376260149",
            "key-20",
            "values--860844060",
            "key-20",
            "values-2003129702",
            "key-75",
            "values--1787311067",
            "key-39",
            "values--1988768973",
            "key-58",
            "values--479797619",
            "key-16",
            "values-571033631",
            "key-65",
            "values--1867296166",
            "key-56",
            "values--2071960469",
            "key-12",
            "values-821930484",
            "key-40",
            "values--54692885",
            "key-65",
            "values-328817493",
            "key-121",
            "values-1276016318",
            "key-33",
            "values--2081652233",
            "key-31",
            "values-381335133",
            "key-77",
            "values-1486312656",
            "key-48",
            "values--1058365372",
            "key-109",
            "values--733344537",
            "key-85",
            "values-1236864082",
            "key-35",
            "values-2045087594",
            "key-49",
            "values-1990762822",
            "key-38",
            "values--1582706513",
            "key-18",
            "values--626997990",
            "key-80",
            "values--1995264473",
            "key-126",
            "values--558193472",
            "key-83",
            "values-415016167",
            "key-53",
            "values-1348674948",
            "key-58",
            "values-612738550",
            "key-12",
            "values-417676134",
            "key-101",
            "values--58098778",
            "key-127",
            "values-1658306930",
            "key-17",
            "values-985378289",
            "key-68",
            "values-686600535",
            "key-36",
            "values-365513638",
            "key-87",
            "values--1737233661",
            "key-67",
            "values--1840935230",
            "key-8",
            "values-540289596",
            "key-11",
            "values--2045114386",
            "key-38",
            "values--786598887",
            "key-48",
            "values-1877144385",
            "key-5",
            "values-65838542",
            "key-18",
            "values-263200779",
            "key-120",
            "values--1500947489",
            "key-65",
            "values-769990109",
            "key-38",
            "values-1886840000",
            "key-29",
            "values--48760205",
            "key-61",
            "values--1942966789");
    failingAction.applyToTestMap(map);
    failingAction.verifyTestMap(map);
  }

  @Test
  void priorFailingCase2() {
    TestCase testCase =
        new TestCase(
            remove("key-34"),
            put("key-122", "values-1828753938"),
            putAll(
                "key-123",
                "values--118789056",
                "key-28",
                "values--751841781",
                "key-105",
                "values-1663318183",
                "key-63",
                "values--2036414463",
                "key-74",
                "values-1584612783",
                "key-118",
                "values--414681411",
                "key-67",
                "values-1154668404",
                "key-1",
                "values--1755856616",
                "key-89",
                "values--344740102",
                "key-110",
                "values-1884649283",
                "key-1",
                "values--1420345075",
                "key-22",
                "values-1951712698",
                "key-103",
                "values-488559164",
                "key-8",
                "values-1180668912",
                "key-44",
                "values-290310046",
                "key-105",
                "values--303926067",
                "key-26",
                "values-910376351",
                "key-59",
                "values-1600204544",
                "key-23",
                "values-425861746",
                "key-76",
                "values--1045446587",
                "key-21",
                "values-453905226",
                "key-1",
                "values-286624672",
                "key-69",
                "values-934359656",
                "key-57",
                "values--1890465763",
                "key-13",
                "values--1949062639",
                "key-68",
                "values-242077328",
                "key-42",
                "values--1584075743",
                "key-46",
                "values--1306318288",
                "key-31",
                "values--848418043",
                "key-71",
                "values--1547961101",
                "key-121",
                "values--1493693636",
                "key-24",
                "values-330660358",
                "key-24",
                "values--1466871690",
                "key-91",
                "values--995064376",
                "key-18",
                "values-1615316779",
                "key-124",
                "values--296191510",
                "key-52",
                "values-740309054",
                "key-8",
                "values-1777392898",
                "key-73",
                "values-92831985",
                "key-13",
                "values--1711360891",
                "key-114",
                "values-1960346620",
                "key-44",
                "values--1599497099",
                "key-107",
                "values-668485357",
                "key-116",
                "values--1792788504"),
            put("key-123", "values--1844485682"),
            putAll(
                "key-64",
                "values--1694520036",
                "key-17",
                "values--469732912",
                "key-79",
                "values--1293521097",
                "key-11",
                "values--2000592955",
                "key-98",
                "values-517073723",
                "key-28",
                "values-1085152681",
                "key-34",
                "values-1943586726",
                "key-3",
                "values-216087991",
                "key-97",
                "values-222660872",
                "key-41",
                "values-90906196",
                "key-63",
                "values--934208984",
                "key-57",
                "values-327167184",
                "key-111",
                "values--1059115125",
                "key-75",
                "values--2031064209",
                "key-8",
                "values-1924310140",
                "key-69",
                "values--362514182",
                "key-90",
                "values-852043703",
                "key-98",
                "values--998302860",
                "key-49",
                "values-1658920804",
                "key-106",
                "values--227162298",
                "key-25",
                "values-493046373",
                "key-52",
                "values--555623542",
                "key-77",
                "values--717275660",
                "key-31",
                "values-1930766287",
                "key-69",
                "values--1367213079",
                "key-38",
                "values--1112081116",
                "key-65",
                "values--1916889923",
                "key-96",
                "values-157036191",
                "key-127",
                "values--302553995",
                "key-38",
                "values-485874872",
                "key-110",
                "values--855874569",
                "key-39",
                "values--390829775",
                "key-7",
                "values--452123269",
                "key-63",
                "values--527204905",
                "key-101",
                "values-166173307",
                "key-126",
                "values-1050454498",
                "key-4",
                "values--215188400",
                "key-25",
                "values-947961204",
                "key-42",
                "values-145803888",
                "key-1",
                "values--970532578",
                "key-43",
                "values--1675493776",
                "key-29",
                "values-1193328809",
                "key-108",
                "values-1302659140",
                "key-120",
                "values--1722764270",
                "key-24",
                "values--483238806",
                "key-53",
                "values-611589672",
                "key-39",
                "values--229429656",
                "key-29",
                "values--733337788",
                "key-9",
                "values-736222322",
                "key-74",
                "values--950770749",
                "key-91",
                "values-202817768",
                "key-95",
                "values-500260096",
                "key-71",
                "values--1798188865",
                "key-12",
                "values--1936098297",
                "key-28",
                "values--2116134632",
                "key-21",
                "values-799594067",
                "key-68",
                "values--333178107",
                "key-50",
                "values-445767791",
                "key-88",
                "values-1307699662",
                "key-69",
                "values--110615017",
                "key-25",
                "values-699603233",
                "key-101",
                "values--2093413536",
                "key-91",
                "values--2022040839",
                "key-45",
                "values-888546703",
                "key-40",
                "values--2140684954",
                "key-1",
                "values-371033654",
                "key-68",
                "values--20293415",
                "key-59",
                "values-697437101",
                "key-43",
                "values--1145022834",
                "key-62",
                "values--2125187195",
                "key-15",
                "values--1062944166",
                "key-103",
                "values--889634836",
                "key-125",
                "values-8694763",
                "key-101",
                "values--281475498",
                "key-13",
                "values-1972488719",
                "key-32",
                "values-1900833863",
                "key-119",
                "values--926978044",
                "key-82",
                "values-288820151",
                "key-78",
                "values--303310027",
                "key-25",
                "values--1284661437",
                "key-47",
                "values-1624726045",
                "key-14",
                "values-1658036950",
                "key-65",
                "values-1629683219",
                "key-10",
                "values-275264679",
                "key-126",
                "values--592085694",
                "key-32",
                "values-1844385705",
                "key-85",
                "values--1815321660",
                "key-72",
                "values-918231225",
                "key-91",
                "values-675699466",
                "key-121",
                "values--2008685332",
                "key-61",
                "values--1398921570",
                "key-19",
                "values-617817427",
                "key-122",
                "values--793708860",
                "key-41",
                "values--2027225350",
                "key-41",
                "values-1194206680",
                "key-1",
                "values-1116090448",
                "key-49",
                "values-1662444555",
                "key-54",
                "values-747436284",
                "key-118",
                "values--1367237858",
                "key-65",
                "values-133495093",
                "key-73",
                "values--1451855551",
                "key-43",
                "values--357794833",
                "key-76",
                "values-129403123",
                "key-59",
                "values--65688873",
                "key-22",
                "values-480031738",
                "key-73",
                "values--310815862",
                "key-0",
                "values--1734944386",
                "key-56",
                "values--540459893",
                "key-38",
                "values-1308912555",
                "key-2",
                "values--2073028093",
                "key-14",
                "values--693713438",
                "key-76",
                "values-295450436",
                "key-113",
                "values--2065146687",
                "key-0",
                "values-2076623027",
                "key-17",
                "values--1394046356",
                "key-78",
                "values--2014478659",
                "key-5",
                "values--665180960"),
            put("key-124", "values-460160716"),
            put("key-112", "values--1828904046"),
            put("key-41", "values--904162962"));

    Map<String, Object> expected = makeMap(testCase);
    OptimizedTagMap actual = makeTagMap(testCase);

    MapAction failingAction = remove("key-127");
    failingAction.applyToExpectedMap(expected);

    failingAction.applyToTestMap(actual);
    failingAction.verifyTestMap(actual);

    assertMapEquals(expected, actual);
  }

  public static final TagMap test(MapAction... actions) {
    return test(new TestCase(Arrays.asList(actions)));
  }

  public static final Map<String, Object> makeMap(TestCase testCase) {
    return makeMap(testCase.actions);
  }

  public static final Map<String, Object> makeMap(MapAction... actions) {
    return makeMap(Arrays.asList(actions));
  }

  public static final Map<String, Object> makeMap(List<MapAction> actions) {
    Map<String, Object> map = new HashMap<>();
    for (MapAction action : actions) {
      action.applyToExpectedMap(map);
    }
    return map;
  }

  public static final OptimizedTagMap makeTagMap(TestCase testCase) {
    return makeTagMap(testCase.actions);
  }

  public static final OptimizedTagMap makeTagMap(MapAction... actions) {
    return makeTagMap(Arrays.asList(actions));
  }

  public static final OptimizedTagMap makeTagMap(List<MapAction> actions) {
    OptimizedTagMap map = new OptimizedTagMap();
    for (MapAction action : actions) {
      action.applyToTestMap(map);
    }
    return map;
  }

  public static final OptimizedTagMap test(TestCase test) {
    List<MapAction> actions = test.actions();

    Map<String, Object> hashMap = new HashMap<>();
    OptimizedTagMap tagMap = new OptimizedTagMap();

    int actionIndex = 0;
    try {
      for (actionIndex = 0; actionIndex < actions.size(); ++actionIndex) {
        MapAction action = actions.get(actionIndex);

        Object expected = action.applyToExpectedMap(hashMap);
        Object actual = action.applyToTestMap(tagMap);

        action.verifyResults(expected, actual);

        action.verifyTestMap(tagMap);

        assertMapEquals(hashMap, tagMap);
      }
    } catch (Error e) {
      System.err.println(new TestCase(actions.subList(0, actionIndex + 1)));

      throw e;
    }
    return tagMap;
  }

  public static final TestCase generateTest() {
    int numActions =
        ThreadLocalRandom.current().nextInt(MAX_NUM_ACTIONS - MIN_NUM_ACTIONS) + MIN_NUM_ACTIONS;
    return generateTest(numActions);
  }

  public static final TestCase generateTest(int size) {
    List<MapAction> actions = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      actions.add(randomAction());
    }
    return new TestCase(actions);
  }

  public static final MapAction randomAction() {
    float actionSelector = ThreadLocalRandom.current().nextFloat();

    switch (randomChoice(0.02, 0.1, 0.2)) {
      case 0:
        return clear();

      case 1:
        return randomChoice(
            () -> putAll(randomKeysAndValues()),
            () -> putAllTagMap(randomKeysAndValues()),
            () -> putAllLedger(randomKeysAndValues()));

      case 2:
        return randomChoice(
            () -> remove(randomKey()),
            () -> removeLight(randomKey()),
            () -> getAndRemove(randomKey()));

      default:
        return randomChoice(
            () -> put(randomKey(), randomValue()),
            () -> set(randomKey(), randomValue()),
            () -> getAndSet(randomKey(), randomValue()));
    }
  }

  public static final MapAction put(String key, String value) {
    return new Put(key, value);
  }

  public static final MapAction set(String key, String value) {
    return new Set(key, value);
  }

  public static final MapAction getAndSet(String key, String value) {
    return new GetAndSet(key, value);
  }

  public static final MapAction putAll(String... keysAndValues) {
    return new PutAll(keysAndValues);
  }

  public static final MapAction putAllTagMap(String... keysAndValues) {
    return new PutAllTagMap(keysAndValues);
  }

  public static final MapAction putAllLedger(String... keysAndValues) {
    return new PutAllLedger(keysAndValues);
  }

  public static final MapAction clear() {
    return Clear.INSTANCE;
  }

  public static final MapAction remove(String key) {
    return new Remove(key);
  }

  public static final MapAction removeLight(String key) {
    return new RemoveLight(key);
  }

  public static final MapAction getAndRemove(String key) {
    return new GetAndRemove(key);
  }

  static final void assertMapEquals(Map<String, Object> expected, OptimizedTagMap actual) {
    // checks entries in both directions to make sure there's full intersection

    for (Map.Entry<String, Object> expectedEntry : expected.entrySet()) {
      TagMap.Entry actualEntry = actual.getEntry(expectedEntry.getKey());
      assertNotNull(actualEntry);
      assertEquals(expectedEntry.getValue(), actualEntry.getValue());
    }

    for (TagMap.Entry actualEntry : actual) {
      Object expectedValue = expected.get(actualEntry.tag());
      assertEquals(expectedValue, actualEntry.objectValue());
    }

    actual.checkIntegrity();
  }

  static final float randomFloat() {
    return ThreadLocalRandom.current().nextFloat();
  }

  static final int randomChoice(int numChoices) {
    return ThreadLocalRandom.current().nextInt(numChoices);
  }

  static final <T> T randomChoice(Supplier<T>... choiceSuppliers) {
    int choice = randomChoice(choiceSuppliers.length);

    return choiceSuppliers[choice].get();
  }

  static final int randomChoice(double... proportions) {
    double selector = ThreadLocalRandom.current().nextDouble();

    for (int i = 0; i < proportions.length; ++i) {
      if (selector < proportions[i]) return i;

      selector -= proportions[i];
    }
    return proportions.length;
  }

  static final String randomKey() {
    return "key-" + ThreadLocalRandom.current().nextInt(NUM_KEYS);
  }

  static final String randomValue() {
    return "values-" + ThreadLocalRandom.current().nextInt();
  }

  static final String[] randomKeysAndValues() {
    int numEntries = ThreadLocalRandom.current().nextInt(NUM_KEYS);

    String[] keysAndValues = new String[numEntries << 1];
    for (int i = 0; i < keysAndValues.length; i += 2) {
      keysAndValues[i] = randomKey();
      keysAndValues[i + 1] = randomValue();
    }
    return keysAndValues;
  }

  static final String literal(String str) {
    return "\"" + str + "\"";
  }

  static final String literalVarArgs(String... strs) {
    StringBuilder builder = new StringBuilder();
    for (String str : strs) {
      if (builder.length() != 0) builder.append(',');
      builder.append(literal(str));
    }
    return builder.toString();
  }

  static final Map<String, String> mapOf(String... keysAndValues) {
    HashMap<String, String> map = new HashMap<>(keysAndValues.length >> 1);
    for (int i = 0; i < keysAndValues.length; i += 2) {
      String key = keysAndValues[i];
      String value = keysAndValues[i + 1];

      map.put(key, value);
    }
    return map;
  }

  static final TagMap tagMapOf(String... keysAndValues) {
    OptimizedTagMap map = new OptimizedTagMap();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      String key = keysAndValues[i];
      String value = keysAndValues[i + 1];

      map.set(key, value);
    }
    map.checkIntegrity();

    return map;
  }

  static final TagMap.Ledger ledgerOf(String... keysAndValues) {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      String key = keysAndValues[i];
      String value = keysAndValues[i + 1];

      ledger.set(key, value);
    }
    return ledger;
  }

  static final class TestCase {
    final List<MapAction> actions;

    TestCase(MapAction... actions) {
      this.actions = Arrays.asList(actions);
    }

    TestCase(List<MapAction> actions) {
      this.actions = actions;
    }

    public final List<MapAction> actions() {
      return this.actions;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      for (MapAction action : this.actions) {
        builder.append(action).append(',').append('\n');
      }
      return builder.toString();
    }
  }

  abstract static class MapAction {
    public abstract Object applyToTestMap(TagMap testMap);

    public abstract Object applyToExpectedMap(Map<String, Object> expectedMap);

    public abstract void verifyResults(Object expected, Object actual);

    public abstract void verifyTestMap(TagMap testMap);

    public abstract String toString();
  }

  abstract static class BasicAction extends MapAction {
    @Override
    public final Object applyToTestMap(TagMap testMap) {
      _applyToTestMap(testMap);

      return void.class;
    }

    protected abstract void _applyToTestMap(TagMap testMap);

    @Override
    public final Object applyToExpectedMap(Map<String, Object> expectedMap) {
      _applyToExpectedMap(expectedMap);

      return void.class;
    }

    protected abstract void _applyToExpectedMap(Map<String, Object> expectedMap);

    public final void verifyResults(Object expected, Object actual) {}
  }

  abstract static class BasicReturningAction<TResult> extends MapAction {
    @Override
    public final TResult applyToTestMap(TagMap testMap) {
      return _applyToTestMap(testMap);
    }

    protected abstract TResult _applyToTestMap(TagMap testMap);

    @Override
    public final TResult applyToExpectedMap(Map<String, Object> expectedMap) {
      return _applyToExpectedMap(expectedMap);
    }

    protected abstract TResult _applyToExpectedMap(Map<String, Object> expectedMap);

    @SuppressWarnings("unchecked")
    public final void verifyResults(Object expected, Object actual) {
      assertEquals(expected, actual);
    }
  }

  abstract static class ReturningAction<TExpectedResult, TActualResult> extends MapAction {
    @Override
    public final TActualResult applyToTestMap(TagMap testMap) {
      return _applyToTestMap(testMap);
    }

    protected abstract TActualResult _applyToTestMap(TagMap testMap);

    @Override
    public final TExpectedResult applyToExpectedMap(Map<String, Object> expectedMap) {
      return _applyToExpectedMap(expectedMap);
    }

    protected abstract TExpectedResult _applyToExpectedMap(Map<String, Object> expectedMap);

    @SuppressWarnings("unchecked")
    public final void verifyResults(Object expected, Object actual) {
      _verifyResults((TExpectedResult) expected, (TActualResult) actual);
    }

    protected abstract void _verifyResults(TExpectedResult expected, TActualResult actual);
  }

  static final class Put extends BasicReturningAction<Object> {
    final String key;
    final String value;

    Put(String key, String value) {
      this.key = key;
      this.value = value;
    }

    @Override
    protected Object _applyToTestMap(TagMap testMap) {
      return testMap.put(this.key, this.value);
    }

    @Override
    protected Object _applyToExpectedMap(Map<String, Object> expectedMap) {
      return expectedMap.put(this.key, this.value);
    }

    @Override
    public void verifyTestMap(TagMap testMap) {
      assertEquals(this.value, testMap.get(this.key));
    }

    @Override
    public String toString() {
      return String.format("put(%s,%s)", literal(this.key), literal(this.value));
    }
  }

  static final class Set extends BasicAction {
    final String key;
    final String value;

    Set(String key, String value) {
      this.key = key;
      this.value = value;
    }

    @Override
    protected void _applyToTestMap(TagMap testMap) {
      testMap.set(this.key, this.value);
    }

    @Override
    protected void _applyToExpectedMap(Map<String, Object> expectedMap) {
      expectedMap.put(this.key, this.value);
    }

    @Override
    public void verifyTestMap(TagMap testMap) {
      assertEquals(this.value, testMap.get(this.key));
    }

    @Override
    public String toString() {
      return String.format("set(%s,%s)", literal(this.key), literal(this.value));
    }
  }

  static final class GetAndSet extends ReturningAction<Object, TagMap.Entry> {
    final String key;
    final String value;

    GetAndSet(String key, String value) {
      this.key = key;
      this.value = value;
    }

    @Override
    protected TagMap.Entry _applyToTestMap(TagMap testMap) {
      return testMap.getAndSet(this.key, this.value);
    }

    @Override
    protected Object _applyToExpectedMap(Map<String, Object> expectedMap) {
      return expectedMap.put(this.key, this.value);
    }

    @Override
    protected void _verifyResults(Object expected, TagMap.Entry actual) {
      if (expected == null) {
        assertNull(actual);
      } else {
        assertEquals(expected, actual.objectValue());
      }
    }

    @Override
    public void verifyTestMap(TagMap testMap) {
      assertEquals(this.value, testMap.get(this.key));
    }

    @Override
    public String toString() {
      return String.format("getAndSet(%s,%s)", literal(this.key), literal(this.value));
    }
  }

  static final class PutAll extends BasicAction {
    final String[] keysAndValues;
    final Map<String, String> map;

    PutAll(String... keysAndValues) {
      this.keysAndValues = keysAndValues;
      this.map = mapOf(keysAndValues);
    }

    @Override
    protected void _applyToTestMap(TagMap testMap) {
      testMap.putAll(this.map);
    }

    @Override
    public void _applyToExpectedMap(Map<String, Object> expectedMap) {
      expectedMap.putAll(this.map);
    }

    @Override
    public void verifyTestMap(TagMap expectedMap) {
      for (Map.Entry<String, String> entry : this.map.entrySet()) {
        assertEquals(entry.getValue(), expectedMap.get(entry.getKey()));
      }
    }

    @Override
    public String toString() {
      return String.format("putAll(%s)", literalVarArgs(this.keysAndValues));
    }
  }

  static final class PutAllTagMap extends BasicAction {
    final String[] keysAndValues;
    final TagMap tagMap;

    PutAllTagMap(String... keysAndValues) {
      this.keysAndValues = keysAndValues;
      this.tagMap = tagMapOf(keysAndValues);
    }

    @Override
    protected void _applyToTestMap(TagMap testMap) {
      testMap.putAll(this.tagMap);
    }

    @Override
    protected void _applyToExpectedMap(Map<String, Object> expectedMap) {
      expectedMap.putAll(this.tagMap);
    }

    @Override
    public void verifyTestMap(TagMap expectedMap) {
      for (TagMap.Entry entry : this.tagMap) {
        assertEquals(entry.objectValue(), expectedMap.get(entry.tag()), "key=" + entry.tag());
      }
    }

    @Override
    public String toString() {
      return String.format("putAllTagMap(%s)", literalVarArgs(this.keysAndValues));
    }
  }

  static final class PutAllLedger extends BasicAction {
    final String[] keysAndValues;
    final TagMap.Ledger ledger;

    PutAllLedger(String... keysAndValues) {
      this.keysAndValues = keysAndValues;
      this.ledger = ledgerOf(keysAndValues);
    }

    @Override
    protected void _applyToTestMap(TagMap testMap) {
      this.ledger.fill(testMap);
    }

    @Override
    protected void _applyToExpectedMap(Map<String, Object> expectedMap) {
      for (TagMap.EntryChange change : this.ledger) {
        // ledgerOf - doesn't produce / removes, so this is safe
        TagMap.Entry entry = (TagMap.Entry) change;
        expectedMap.put(entry.tag(), entry.objectValue());
      }
    }

    @Override
    public void verifyTestMap(TagMap expectedMap) {
      // ledger may contain multiple updates of the same key
      // easier to produce a TagMap and check against it

      for (TagMap.Entry entry : this.ledger.buildImmutable()) {
        assertEquals(entry.objectValue(), expectedMap.get(entry.tag()), "key=" + entry.tag());
      }
    }

    @Override
    public String toString() {
      return String.format("putAllLedger(%s)", literalVarArgs(this.keysAndValues));
    }
  }

  static final class Remove extends BasicReturningAction<Object> {
    final String key;

    Remove(String key) {
      this.key = key;
    }

    @Override
    protected Object _applyToTestMap(TagMap testMap) {
      return testMap.remove((Object) this.key);
    }

    @Override
    protected Object _applyToExpectedMap(Map<String, Object> expectedMap) {
      return expectedMap.remove(this.key);
    }

    @Override
    public void verifyTestMap(TagMap testMap) {
      assertFalse(testMap.containsKey(this.key));
    }

    @Override
    public String toString() {
      return String.format("remove(%s)", literal(this.key));
    }
  }

  static final class RemoveLight extends ReturningAction<Object, Boolean> {
    final String key;

    RemoveLight(String key) {
      this.key = key;
    }

    @Override
    protected Boolean _applyToTestMap(TagMap testMap) {
      return testMap.remove(this.key);
    }

    @Override
    protected Object _applyToExpectedMap(Map<String, Object> expectedMap) {
      return expectedMap.remove(this.key);
    }

    @Override
    protected void _verifyResults(Object expected, Boolean actual) {
      assertEquals((expected != null), actual);
    }

    @Override
    public void verifyTestMap(TagMap testMap) {
      assertFalse(testMap.containsKey(this.key));
    }

    @Override
    public String toString() {
      return String.format("removeLight(%s)", literal(this.key));
    }
  }

  static final class GetAndRemove extends ReturningAction<Object, TagMap.Entry> {
    final String key;

    GetAndRemove(String key) {
      this.key = key;
    }

    @Override
    protected TagMap.Entry _applyToTestMap(TagMap testMap) {
      return testMap.getAndRemove(this.key);
    }

    @Override
    protected Object _applyToExpectedMap(Map<String, Object> expectedMap) {
      return expectedMap.remove(this.key);
    }

    @Override
    protected void _verifyResults(Object expected, TagMap.Entry actual) {
      if (expected == null) {
        assertNull(actual);
      } else {
        assertEquals(expected, actual.objectValue());
      }
    }

    @Override
    public void verifyTestMap(TagMap testMap) {
      assertFalse(testMap.containsKey(this.key));
    }

    @Override
    public String toString() {
      return String.format("getAndRemove(%s)", literal(this.key));
    }
  }

  static final class Clear extends BasicAction {
    static final Clear INSTANCE = new Clear();

    private Clear() {}

    @Override
    protected void _applyToTestMap(TagMap testMap) {
      testMap.clear();
    }

    @Override
    protected void _applyToExpectedMap(Map<String, Object> mapUnderTest) {
      mapUnderTest.clear();
    }

    @Override
    public void verifyTestMap(TagMap testMap) {
      assertTrue(testMap.isEmpty());
    }

    @Override
    public String toString() {
      return "clear()";
    }
  }
}
