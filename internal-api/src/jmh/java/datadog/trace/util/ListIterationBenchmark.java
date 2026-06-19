package datadog.trace.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
 *
 * <code>Java 17 - MacBook M1 - 8 threads - Fork 2
 * Benchmark                                                                          (listSpec)   Mode  Cnt           Score             Error   Units
 * ListIterationBenchmark.cstyleFor_inline                                COLLECTIONS_EMPTY_LIST  thrpt    6  3441846811.051 ±  165799496.772   ops/s
 * ListIterationBenchmark.cstyleFor_inline:gc.alloc.rate.norm             COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                           COLLECTIONS_EMPTY_LIST  thrpt    6  4693760384.130 ±  460489203.723   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm        COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                      EMPTY_ARRAY_LIST  thrpt    6  3207300169.295 ±  133897191.487   ops/s
 * ListIterationBenchmark.cstyleFor_inline:gc.alloc.rate.norm                   EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                 EMPTY_ARRAY_LIST  thrpt    6  4579930434.661 ±  197651384.556   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm              EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                        SINGLETON_LIST  thrpt    6   321884630.577 ±   65533091.652   ops/s
 * ListIterationBenchmark.cstyleFor_inline:gc.alloc.rate.norm                     SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                   SINGLETON_LIST  thrpt    6   278928689.529 ±  136125824.149   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                          ARRAY_LIST_1  thrpt    6   239566270.808 ±   57510999.361   ops/s
 * ListIterationBenchmark.cstyleFor_inline:gc.alloc.rate.norm                       ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                     ARRAY_LIST_1  thrpt    6   247272946.887 ±  163358527.661   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                          ARRAY_LIST_5  thrpt    6    82032733.693 ±    5895792.103   ops/s
 * ListIterationBenchmark.cstyleFor_inline:gc.alloc.rate.norm                       ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                     ARRAY_LIST_5  thrpt    6    61087304.985 ±   19150181.071   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                         ARRAY_LIST_10  thrpt    6    40031225.068 ±    4972953.693   ops/s
 * ListIterationBenchmark.cstyleFor_inline:gc.alloc.rate.norm                      ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                    ARRAY_LIST_10  thrpt    6    36037195.765 ±    5131727.309   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                 ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.cstyleFor_inline                                        ARRAY_LIST_100  thrpt    6     4716494.077 ±     175625.330   ops/s
 * ListIterationBenchmark.cstyleFor_inline:gc.alloc.rate.norm                     ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.cstyleFor_dont_inline                                   ARRAY_LIST_100  thrpt    6     4669066.176 ±     382309.794   ops/s
 * ListIterationBenchmark.cstyleFor_dont_inline:gc.alloc.rate.norm                ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 *
 * ListIterationBenchmark.enhancedFor_inline                              COLLECTIONS_EMPTY_LIST  thrpt    6  2791971679.099 ±  111873232.841   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm           COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                         COLLECTIONS_EMPTY_LIST  thrpt    6  2719830885.462 ±  118403637.971   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm      COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                    EMPTY_ARRAY_LIST  thrpt    6  2928950733.208 ±  860575152.461   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                 EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                               EMPTY_ARRAY_LIST  thrpt    6  2511906662.257 ±  115828875.854   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm            EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                      SINGLETON_LIST  thrpt    6   319417005.925 ±   66392316.641   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                   SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                 SINGLETON_LIST  thrpt    6   372974563.302 ±   72936400.165   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm              SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                        ARRAY_LIST_1  thrpt    6   253470787.790 ±   45149043.464   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                     ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                   ARRAY_LIST_1  thrpt    6   275479917.244 ±  134785506.568   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm                ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                        ARRAY_LIST_5  thrpt    6    77307523.480 ±   21655027.849   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                     ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                   ARRAY_LIST_5  thrpt    6    87757982.720 ±   11646913.156   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm                ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                       ARRAY_LIST_10  thrpt    6    34582795.172 ±    2277582.826   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                    ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                  ARRAY_LIST_10  thrpt    6    42658734.741 ±    1565239.588   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm               ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.enhancedFor_inline                                      ARRAY_LIST_100  thrpt    6     4500662.619 ±     458804.727   ops/s
 * ListIterationBenchmark.enhancedFor_inline:gc.alloc.rate.norm                   ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.enhancedFor_dont_inline                                 ARRAY_LIST_100  thrpt    6     5364328.724 ±     796373.387   ops/s
 * ListIterationBenchmark.enhancedFor_dont_inline:gc.alloc.rate.norm              ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 *
 * ListIterationBenchmark.forEach_dont_inline                             COLLECTIONS_EMPTY_LIST  thrpt    6  3427531516.621 ±  136844953.098   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm          COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                  COLLECTIONS_EMPTY_LIST  thrpt    6  3401640098.513 ±  133456586.319   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm               COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                   EMPTY_ARRAY_LIST  thrpt    6  3200703797.201 ±  211068414.587   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                        EMPTY_ARRAY_LIST  thrpt    6  3215780693.079 ±   83146708.893   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                     EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                          SINGLETON_LIST  thrpt    6   333466633.365 ±   88901288.443   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                       SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                     SINGLETON_LIST  thrpt    6   321163415.952 ±   86007051.008   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                  SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                            ARRAY_LIST_1  thrpt    6   276172673.167 ±   77908134.118   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                         ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                       ARRAY_LIST_1  thrpt    6   246624762.016 ±   15306125.645   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                            ARRAY_LIST_5  thrpt    6    87720014.120 ±   27715027.106   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                         ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                       ARRAY_LIST_5  thrpt    6    85158415.126 ±    3496971.118   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                           ARRAY_LIST_10  thrpt    6    34553504.978 ±    6862692.219   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                        ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                      ARRAY_LIST_10  thrpt    6    34842477.542 ±    3814763.538   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.forEach_inline                                          ARRAY_LIST_100  thrpt    6     4811830.148 ±    1340605.559   ops/s
 * ListIterationBenchmark.forEach_inline:gc.alloc.rate.norm                       ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.forEach_dont_inline                                     ARRAY_LIST_100  thrpt    6     4544904.068 ±     324694.949   ops/s
 * ListIterationBenchmark.forEach_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 *
 * ListIterationBenchmark.iterator_inline                                 COLLECTIONS_EMPTY_LIST  thrpt    6  3359558430.152 ±  404383428.668   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm              COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                            COLLECTIONS_EMPTY_LIST  thrpt    6  3433230085.533 ±  168311857.573   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm         COLLECTIONS_EMPTY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                       EMPTY_ARRAY_LIST  thrpt    6  3261054045.617 ±  230129899.469   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                    EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                  EMPTY_ARRAY_LIST  thrpt    6  3206102591.326 ±  162666444.955   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm               EMPTY_ARRAY_LIST  thrpt    6          ≈ 10⁻⁷                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                         SINGLETON_LIST  thrpt    6   331489476.477 ±   83900966.078   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                      SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                    SINGLETON_LIST  thrpt    6   377189128.976 ±  107802563.668   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                 SINGLETON_LIST  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                           ARRAY_LIST_1  thrpt    6   344624730.754 ±   16714262.973   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                        ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                      ARRAY_LIST_1  thrpt    6   265191798.107 ±   50433283.246   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_1  thrpt    6          ≈ 10⁻⁶                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                           ARRAY_LIST_5  thrpt    6    83638808.579 ±   28683758.671   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                        ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                      ARRAY_LIST_5  thrpt    6    80787244.289 ±   13548825.106   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_5  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                          ARRAY_LIST_10  thrpt    6    51870395.372 ±    6285601.386   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                       ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                     ARRAY_LIST_10  thrpt    6    36344588.246 ±    7084099.030   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_10  thrpt    6          ≈ 10⁻⁵                      B/op
 *
 * ListIterationBenchmark.iterator_inline                                         ARRAY_LIST_100  thrpt    6     6307755.121 ±     557388.178   ops/s
 * ListIterationBenchmark.iterator_inline:gc.alloc.rate.norm                      ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 * ListIterationBenchmark.iterator_dont_inline                                    ARRAY_LIST_100  thrpt    6     4780011.396 ±     987345.508   ops/s
 * ListIterationBenchmark.iterator_dont_inline:gc.alloc.rate.norm                 ARRAY_LIST_100  thrpt    6          ≈ 10⁻⁴                      B/op
 *
 *
 * ListIterationBenchmark.parallelStreams_inline                          COLLECTIONS_EMPTY_LIST  thrpt    6   120936524.095 ±   27406664.594   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm       COLLECTIONS_EMPTY_LIST  thrpt    6         128.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                     COLLECTIONS_EMPTY_LIST  thrpt    6   117725319.706 ±   17140950.267   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm  COLLECTIONS_EMPTY_LIST  thrpt    6         128.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                EMPTY_ARRAY_LIST  thrpt    6   126621266.650 ±    6027180.747   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm             EMPTY_ARRAY_LIST  thrpt    6         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                           EMPTY_ARRAY_LIST  thrpt    6   104930321.022 ±   34783325.818   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm        EMPTY_ARRAY_LIST  thrpt    6         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                  SINGLETON_LIST  thrpt    6    16774561.421 ±      582072.172   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm               SINGLETON_LIST  thrpt    6         152.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                             SINGLETON_LIST  thrpt    6    16119756.297 ±      313920.576   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm          SINGLETON_LIST  thrpt    6         152.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                    ARRAY_LIST_1  thrpt    6    21404468.390 ±    1052683.393   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm                 ARRAY_LIST_1  thrpt    6         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                               ARRAY_LIST_1  thrpt    6    21321342.506 ±    1871460.083   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm            ARRAY_LIST_1  thrpt    6         160.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                    ARRAY_LIST_5  thrpt    6     2504163.930 ±     263550.892   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm                 ARRAY_LIST_5  thrpt    6         480.063 ±           0.017    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                               ARRAY_LIST_5  thrpt    6     2289397.508 ±     565554.783   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm            ARRAY_LIST_5  thrpt    6         480.089 ±           0.041    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                   ARRAY_LIST_10  thrpt    6     1346887.163 ±     373998.298   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm                ARRAY_LIST_10  thrpt    6         880.110 ±           0.045    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                              ARRAY_LIST_10  thrpt    6     1140354.960 ±     189575.120   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm           ARRAY_LIST_10  thrpt    6         880.177 ±           0.091    B/op
 *
 * ListIterationBenchmark.parallelStreams_inline                                  ARRAY_LIST_100  thrpt    6      285244.550 ±     138634.195   ops/s
 * ListIterationBenchmark.parallelStreams_inline:gc.alloc.rate.norm               ARRAY_LIST_100  thrpt    6        5201.037 ±           1.468    B/op
 *
 * ListIterationBenchmark.parallelStreams_dont_inline                             ARRAY_LIST_100  thrpt    6      253984.999 ±      25333.775   ops/s
 * ListIterationBenchmark.parallelStreams_dont_inline:gc.alloc.rate.norm          ARRAY_LIST_100  thrpt    6        5200.636 ±           0.278    B/op
 *
 *
 * ListIterationBenchmark.streams_inline                                  COLLECTIONS_EMPTY_LIST  thrpt    6  1001084687.071 ±  437785554.745   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm               COLLECTIONS_EMPTY_LIST  thrpt    6          56.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                             COLLECTIONS_EMPTY_LIST  thrpt    6   929213084.624 ±  142946810.333   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm          COLLECTIONS_EMPTY_LIST  thrpt    6          56.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                        EMPTY_ARRAY_LIST  thrpt    6   584212874.954 ±  209743095.690   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                     EMPTY_ARRAY_LIST  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                   EMPTY_ARRAY_LIST  thrpt    6   270680135.068 ±   12934279.122   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                EMPTY_ARRAY_LIST  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                          SINGLETON_LIST  thrpt    6    41450495.098 ±   15155978.655   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                       SINGLETON_LIST  thrpt    6          80.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                     SINGLETON_LIST  thrpt    6    32201013.051 ±    3839804.719   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                  SINGLETON_LIST  thrpt    6          80.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                            ARRAY_LIST_1  thrpt    6    34861920.999 ±    6424967.299   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                         ARRAY_LIST_1  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                       ARRAY_LIST_1  thrpt    6    32473385.005 ±    8663289.921   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_1  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                            ARRAY_LIST_5  thrpt    6    24922696.394 ±    3309353.634   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                         ARRAY_LIST_5  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                       ARRAY_LIST_5  thrpt    6    21741745.391 ±    5026648.521   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                    ARRAY_LIST_5  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                           ARRAY_LIST_10  thrpt    6    12766807.560 ±    7128658.860   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                        ARRAY_LIST_10  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                      ARRAY_LIST_10  thrpt    6    12683336.048 ±    2867751.497   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                   ARRAY_LIST_10  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_inline                                          ARRAY_LIST_100  thrpt    6     3900281.683 ±     471999.748   ops/s
 * ListIterationBenchmark.streams_inline:gc.alloc.rate.norm                       ARRAY_LIST_100  thrpt    6          88.000 ±           0.001    B/op
 *
 * ListIterationBenchmark.streams_dont_inline                                     ARRAY_LIST_100  thrpt    6     5049831.655 ±    1023034.564   ops/s
 * ListIterationBenchmark.streams_dont_inline:gc.alloc.rate.norm                  ARRAY_LIST_100  thrpt    6          88.000 ±           0.001    B/op
 * </code>
 */
@Fork(2)
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
    listSpec.list.parallelStream().forEach(Element::manipulate_inline);
  }

  @Benchmark
  public void parallelStreams_dont_inline() {
    listSpec.list.parallelStream().forEach(Element::manipulate_dont_inline);
  }
}
