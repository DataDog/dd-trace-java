package datadog.trace.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.CompilerControl.Mode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmark comparing difference ways to iterate list of different types and sizes -- both with
 * simple loop bodies (inline case) and complicated loop bodies (dont inline case).
 *
 * <ul>
 *   Compares...
 *   <li>(RECOMMENDED) enhanced for loop / iterator - usually most performant, since escape analysis
 *       usually eliminates iterator allocation
 *   <li>(SITUATIONAL) List.forEach - good when using a non-capturing lambda, when escape analysis
 *       fails to eliminate iterator allocation - good alternative
 *   <li>(SITUATIONAL) c-style i=0; i < list.size() - usually worse than enhanced for - might be
 *       useful with complicated loop body when escape analysis fails to eliminate the iterator
 *   <li>(DISCOURAGED) List.stream - always incurs allocation overhead - usually unnecessary
 *   <li>(DISCOURAGED) List.parallelStream - heavy allocation overhead - only beneficial when
 *       working with sets (uncommon in the java agent)
 *   <li>
 * </ul>
 * <code>Java 17 - MacBook M1 - 8 threads
 * Benchmark                                                                          (listSpec)   Mode  Cnt           Score             Error   Units
 * ListIterationBenchmark.cstyleFor_inline                                COLLECTIONS_EMPTY_LIST  thrpt    3  9066154714.207 ±  3993855570.335   ops/s
 * ListIterationBenchmark.cstyleFor:gc.alloc.rate.norm                    COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                           COLLECTIONS_EMPTY_LIST  thrpt    3  9307532101.544 ±  3600114064.312   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm        COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                      EMPTY_ARRAY_LIST  thrpt    3  8553022013.203 ±  4941170671.582   ops/s
 * ListIterationBenchmark.cstyleFor_inline :gc.alloc.rate.norm                  EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                 EMPTY_ARRAY_LIST  thrpt    3  8096029334.875 ±  3735770834.739   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm              EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                        SINGLETON_LIST  thrpt    3   579968267.534 ±   480993460.419   ops/s
 * ListIterationBenchmark.cstyleFor_inline :gc.alloc.rate.norm                    SINGLETON_LIST  thrpt    3          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                   SINGLETON_LIST  thrpt    3   219512282.514 ±    10114065.364   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                SINGLETON_LIST  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                          ARRAY_LIST_1  thrpt    3   445550609.183 ±   430016640.001   ops/s
 * ListIterationBenchmark.cstyleFor_inline :gc.alloc.rate.norm                      ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                     ARRAY_LIST_1  thrpt    3   257920434.103 ±   499635383.643   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                          ARRAY_LIST_5  thrpt    3    75497912.945 ±    30020599.171   ops/s
 * ListIterationBenchmark.cstyleFor_inline :gc.alloc.rate.norm                      ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                     ARRAY_LIST_5  thrpt    3    28476601.001 ±     1230275.296   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 *
 * ListIterationBenchmark.cstyleFor_inline                                         ARRAY_LIST_10  thrpt    3    29817752.733 ±    20822258.640   ops/s
 * ListIterationBenchmark.cstyleFor_inline :gc.alloc.rate.norm                     ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                    ARRAY_LIST_10  thrpt    3    10586304.137 ±      694080.794   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                 ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                        ARRAY_LIST_100  thrpt    3     5189749.889 ±      182890.132   ops/s
 * ListIterationBenchmark.cstyleFor_inline :gc.alloc.rate.norm                    ARRAY_LIST_100  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                   ARRAY_LIST_100  thrpt    3     5574779.347 ±     3138942.124   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                ARRAY_LIST_100  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                              COLLECTIONS_EMPTY_LIST  thrpt    3  9207677799.793 ±  1391109060.707   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm           COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                         COLLECTIONS_EMPTY_LIST  thrpt    3  9223840664.732 ±  3042465993.695   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm      COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                    EMPTY_ARRAY_LIST  thrpt    3  8395252508.254 ±  3316954375.722   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                 EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                               EMPTY_ARRAY_LIST  thrpt    3  8749632223.603 ±  5103144323.039   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm            EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                      SINGLETON_LIST  thrpt    3   585380967.338 ±   150306592.315   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                   SINGLETON_LIST  thrpt    3          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                 SINGLETON_LIST  thrpt    3   401275107.625 ±  1875412135.090   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm              SINGLETON_LIST  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                        ARRAY_LIST_1  thrpt    3   239663416.496 ±     9797263.026   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                     ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                   ARRAY_LIST_1  thrpt    3   292347248.552 ±   402874274.980   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm                ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                        ARRAY_LIST_5  thrpt    3   114233676.386 ±    17033961.163   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                     ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                   ARRAY_LIST_5  thrpt    3    17788070.719 ±      185801.986   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm                ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                       ARRAY_LIST_10  thrpt    3    36526081.949 ±     5409614.800   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                    ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                  ARRAY_LIST_10  thrpt    3     9952121.906 ±      541730.002   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm               ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                      ARRAY_LIST_100  thrpt    3     5021433.149 ±      189172.874   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                   ARRAY_LIST_100  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                 ARRAY_LIST_100  thrpt    3     3787184.732 ±      122019.171   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm              ARRAY_LIST_100  thrpt    3          ≈ 10⁻³                      B/op
 *
 *
 * ListIterationBenchmark.forEach_dont_inline                             COLLECTIONS_EMPTY_LIST  thrpt    3  9087818339.363 ±  4682854417.372   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm          COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                  COLLECTIONS_EMPTY_LIST  thrpt    3  9236676927.205 ±  8654546805.544   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm               COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                   EMPTY_ARRAY_LIST  thrpt    3  9067901137.791 ±   658593480.822   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                        EMPTY_ARRAY_LIST  thrpt    3  8338589922.946 ±  2762463965.925   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                     EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                          SINGLETON_LIST  thrpt    3   273193041.510 ±   232676409.952   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                       SINGLETON_LIST  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                     SINGLETON_LIST  thrpt    3   429048764.107 ±   122641686.349   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                  SINGLETON_LIST  thrpt    3          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                            ARRAY_LIST_1  thrpt    3   191331395.539 ±    21424694.743   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                         ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                       ARRAY_LIST_1  thrpt    3   131771385.351 ±     5791600.995   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                            ARRAY_LIST_5  thrpt    3    61657233.796 ±    22827021.020   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                         ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                       ARRAY_LIST_5  thrpt    3    27739644.723 ±     2168701.924   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                           ARRAY_LIST_10  thrpt    3    28127208.294 ±    26550020.011   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                        ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                      ARRAY_LIST_10  thrpt    3    12826780.510 ±     1545440.613   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                          ARRAY_LIST_100  thrpt    3     4919956.616 ±     2482616.871   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                       ARRAY_LIST_100  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                     ARRAY_LIST_100  thrpt    3     3631999.182 ±     2290995.458   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_100  thrpt    3          ≈ 10⁻³                      B/op
 *
 *
 * ListIterationBenchmark.iterator_inline                                 COLLECTIONS_EMPTY_LIST  thrpt    3  8782837307.595 ±  9193909313.868   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm              COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                            COLLECTIONS_EMPTY_LIST  thrpt    3  9077833391.678 ±  9363495032.329   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm         COLLECTIONS_EMPTY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                       EMPTY_ARRAY_LIST  thrpt    3  7577428097.018 ± 17869599838.589   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                    EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                  EMPTY_ARRAY_LIST  thrpt    3  8905180606.486 ±  1278759944.669   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm               EMPTY_ARRAY_LIST  thrpt    3          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                         SINGLETON_LIST  thrpt    3   545492858.104 ±   288175308.591   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                      SINGLETON_LIST  thrpt    3          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                    SINGLETON_LIST  thrpt    3   227010872.669 ±    23119526.801   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                 SINGLETON_LIST  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                           ARRAY_LIST_1  thrpt    3   228450106.295 ±   118964448.603   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                        ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                      ARRAY_LIST_1  thrpt    3   137387128.594 ±    25909582.512   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_1  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                           ARRAY_LIST_5  thrpt    3    76164387.317 ±     8753181.873   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                        ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                      ARRAY_LIST_5  thrpt    3    23222672.053 ±    14620774.912   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_5  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                          ARRAY_LIST_10  thrpt    3    32207574.764 ±     8935430.248   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                       ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                     ARRAY_LIST_10  thrpt    3     7744943.832 ±     4007932.991   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_10  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                         ARRAY_LIST_100  thrpt    3     4858523.049 ±     1169051.166   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                      ARRAY_LIST_100  thrpt    3          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                    ARRAY_LIST_100  thrpt    3     3573806.058 ±     1033738.003   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                 ARRAY_LIST_100  thrpt    3          ≈ 10⁻³                      B/op
 *
 *
 * ListIterationBenchmark.parallelStreams_inline                          COLLECTIONS_EMPTY_LIST  thrpt    3   378041146.558 ±   143697638.943   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm       COLLECTIONS_EMPTY_LIST  thrpt    3         128.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                     COLLECTIONS_EMPTY_LIST  thrpt    3   350864577.375 ±   117736321.914   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm  COLLECTIONS_EMPTY_LIST  thrpt    3         128.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                EMPTY_ARRAY_LIST  thrpt    3   324256295.000 ±    62831502.030   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm             EMPTY_ARRAY_LIST  thrpt    3         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                           EMPTY_ARRAY_LIST  thrpt    3  1044022834.772 ±  4619766802.708   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm             EMPTY_ARRAY_LIST  thrpt    3         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                  SINGLETON_LIST  thrpt    3    18501339.741 ±     1654479.836   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm               SINGLETON_LIST  thrpt    3         152.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                             SINGLETON_LIST  thrpt    3    21809861.051 ±      350120.124   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm               SINGLETON_LIST  thrpt    3         152.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                    ARRAY_LIST_1  thrpt    3    18012814.959 ±     1696186.799   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm                 ARRAY_LIST_1  thrpt    3         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                               ARRAY_LIST_1  thrpt    3   111167193.920 ±    22996298.573   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm                 ARRAY_LIST_1  thrpt    3         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                    ARRAY_LIST_5  thrpt    3     2012388.452 ±      949228.198   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm                 ARRAY_LIST_5  thrpt    3         480.299 ±           0.347    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                               ARRAY_LIST_5  thrpt    3     2128440.686 ±      511599.227   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm            ARRAY_LIST_5  thrpt    3         480.254 ±           0.357    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                   ARRAY_LIST_10  thrpt    3     1303577.389 ±      929027.756   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm                ARRAY_LIST_10  thrpt    3         880.359 ±           0.262    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                              ARRAY_LIST_10  thrpt    3     1311148.884 ±      126593.404   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm           ARRAY_LIST_10  thrpt    3         880.457 ±           0.052    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                  ARRAY_LIST_100  thrpt    3      581725.185 ±       79053.756   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm               ARRAY_LIST_100  thrpt    3        5200.204 ±           0.339    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                             ARRAY_LIST_100  thrpt    3      535792.621 ±      171447.687   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm          ARRAY_LIST_100  thrpt    3        5200.636 ±          2.516     B/op
 *
 *
 * ListIterationBenchmark.streams_inline                                  COLLECTIONS_EMPTY_LIST  thrpt    3  1908867078.365 ±   457707512.391   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm               COLLECTIONS_EMPTY_LIST  thrpt    3          56.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                             COLLECTIONS_EMPTY_LIST  thrpt    3  1921592196.919 ±   244634653.490   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm          COLLECTIONS_EMPTY_LIST  thrpt    3          56.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                        EMPTY_ARRAY_LIST  thrpt    3  1214862597.257 ±   135140736.401   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                     EMPTY_ARRAY_LIST  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                   EMPTY_ARRAY_LIST  thrpt    3  1224109308.819 ±   128448610.019   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                EMPTY_ARRAY_LIST  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                          SINGLETON_LIST  thrpt    3    38323049.906 ±    12454289.128   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                       SINGLETON_LIST  thrpt    3          80.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                     SINGLETON_LIST  thrpt    3    23491667.001 ±     7585146.466   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                  SINGLETON_LIST  thrpt    3          80.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                            ARRAY_LIST_1  thrpt    3   196494080.731 ±   111300975.392   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                         ARRAY_LIST_1  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                       ARRAY_LIST_1  thrpt    3   118268890.253 ±    13653144.114   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_1  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                            ARRAY_LIST_5  thrpt    3    69135875.825 ±     3742040.817   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                         ARRAY_LIST_5  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                       ARRAY_LIST_5  thrpt    3    46099259.535 ±    29749609.625   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_5  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                           ARRAY_LIST_10  thrpt    3    14923107.542 ±     5663775.999   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                        ARRAY_LIST_10  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                      ARRAY_LIST_10  thrpt    3    17238302.629 ±     3449711.016   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_10  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                          ARRAY_LIST_100  thrpt    3     4974152.515 ±     1040136.146   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                       ARRAY_LIST_100  thrpt    3          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                     ARRAY_LIST_100  thrpt    3     5781143.104 ±     2907139.961   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_100  thrpt    3          88.000 ±           0.001    B/op
 * </code>
 */
@Fork(1)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Benchmark)
public class ListIterationBenchmark {
  public static final class Element {
    int num = 0;

    @CompilerControl(Mode.INLINE)
    void manipulate_inline() {
      this.num += 1;
    }

    @CompilerControl(Mode.DONT_INLINE)
    void manipulate_dont_inline() {
      this.num += 1;
    }
  }

  static ArrayList<Element> newArrayList(int size) {
    ArrayList<Element> newList = new ArrayList<>(size);
    for (int i = 0; i < size; ++i) {
      newList.add(new Element());
    }
    return newList;
  }

  public enum ListSpec {
    COLLECTIONS_EMPTY_LIST(Collections.emptyList()),
    EMPTY_ARRAY_LIST(new ArrayList<>()),
    SINGLETON_LIST(Collections.singletonList(new Element())),
    ARRAY_LIST_1(newArrayList(1)),
    ARRAY_LIST_5(newArrayList(5)),
    ARRAY_LIST_10(newArrayList(10)),
    ARRAY_LIST_100(newArrayList(100));

    final List<Element> list;

    ListSpec(List<Element> list) {
      this.list = list;
    }
  }

  @Param ListSpec listSpec;

  @Benchmark
  public void forEach_inline() {
    this.listSpec.list.forEach(Element::manipulate_inline);
  }

  @Benchmark
  public void forEach_dont_inline() {
    this.listSpec.list.forEach(Element::manipulate_dont_inline);
  }

  @Benchmark
  public void enhancedFor_inline() {
    // Enhanced for-loop is just syntax sugar for an Iterator
    for (Element e : this.listSpec.list) {
      e.manipulate_inline();
    }
  }

  @Benchmark
  public void enhancedFor_dont_inline() {
    // Enhanced for-loop is just syntax sugar for an Iterator
    for (Element e : this.listSpec.list) {
      e.manipulate_dont_inline();
    }
  }

  @Benchmark
  public void iterator_inline() {
    for (Iterator<Element> iter = this.listSpec.list.iterator(); iter.hasNext(); ) {
      iter.next().manipulate_inline();
    }
  }

  @Benchmark
  public void iterator_dont_inline() {
    for (Iterator<Element> iter = this.listSpec.list.iterator(); iter.hasNext(); ) {
      iter.next().manipulate_dont_inline();
    }
  }

  @Benchmark
  public void cstyleFor_inline() {
    for (int i = 0; i < this.listSpec.list.size(); ++i) {
      this.listSpec.list.get(i).manipulate_inline();
    }
  }

  @Benchmark
  public void cstyleFor_dont_inline() {
    for (int i = 0; i < this.listSpec.list.size(); ++i) {
      this.listSpec.list.get(i).manipulate_dont_inline();
    }
  }

  @Benchmark
  public void streams_inline() {
    this.listSpec.list.stream().forEach(Element::manipulate_inline);
  }

  @Benchmark
  public void streams_dont_inline() {
    this.listSpec.list.stream().forEach(Element::manipulate_dont_inline);
  }

  @Benchmark
  public void parallelStreams_inline() {
    listSpec.list.parallelStream().forEach(Element::manipulate_dont_inline);
  }

  @Benchmark
  public void parallelStreams_dont_inline() {
    listSpec.list.parallelStream().forEach(Element::manipulate_dont_inline);
  }
}
