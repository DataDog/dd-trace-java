package datadog.trace.api.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SQLNormalizerTest extends DDJavaSpecification {

  @ParameterizedTest(name = "[{index}] normalize {0}")
  @MethodSource("testNormalizeArguments")
  void testNormalize(String sql, String expected) {
    UTF8BytesString normalized = SQLNormalizer.normalize(sql);

    assertEquals(expected, normalized.toString());
  }

  static Stream<Arguments> testNormalizeArguments() {
    return Stream.of(
        arguments("", ""),
        arguments("   ", "   "),
        arguments("         ", "         "),
        arguments("罿", "罿"),
        arguments("罿潯", "罿潯"),
        arguments("罿潯罿潯罿潯罿潯罿潯", "罿潯罿潯罿潯罿潯罿潯"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc1287681964'",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681964'",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964'",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc1287681964\\''",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964\\''",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681\\'964'",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'964'",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964'",
            "SELECT * FROM TABLE WHERE userId = ?"),
        arguments(
            "SELECT * FROM TABLE WHERE userId IN ('a', 'b', 'c')",
            "SELECT * FROM TABLE WHERE userId IN (?, ?, ?)"),
        arguments(
            "SELECT * FROM TABLE WHERE userId IN ('abc\\'1287681\\'964', "
                + "'abc\\'1287\\'681\\'\\'\\'\\'964', 'abc\\'1287\\'681\\'964')",
            "SELECT * FROM TABLE WHERE userId IN (?, ?, ?)"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc1287681964' ORDER BY FOO DESC",
            "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964' ORDER BY FOO DESC",
            "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"),
        arguments(
            "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar",
            "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar"),
        arguments("CREATE TABLE \"VALUE\"", "CREATE TABLE \"VALUE\""),
        arguments(
            "INSERT INTO \"VALUE\" (\"column\") VALUES ('ljahklshdlKASH')",
            "INSERT INTO \"VALUE\" (\"column\") VALUES (?)"),
        arguments(
            "INSERT INTO \"VALUE\" (\"col1\",\"col2\",\"col3\") VALUES ('blah',12983,X'ff')",
            "INSERT INTO \"VALUE\" (\"col1\",\"col2\",\"col3\") VALUES (?,?,?)"),
        arguments(
            "INSERT INTO \"VALUE\" (\"col1\", \"col2\", \"col3\") VALUES ('blah',12983,X'ff')",
            "INSERT INTO \"VALUE\" (\"col1\", \"col2\", \"col3\") VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE (col1,col2,col3) VALUES ('blah',12983,X'ff')",
            "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE (col1,col2,col3) VALUES (12983,X'ff','blah')",
            "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff','blah',12983)",
            "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE (col1,col2,col3) VALUES ('a','b',1)",
            "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE (col1, col2, col3) VALUES ('a','b',1)",
            "INSERT INTO VALUE (col1, col2, col3) VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE ( col1, col2, col3 ) VALUES ('a','b',1)",
            "INSERT INTO VALUE ( col1, col2, col3 ) VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE (col1,col2,col3) VALUES ('a', 'b' ,1)",
            "INSERT INTO VALUE (col1,col2,col3) VALUES (?, ? ,?)"),
        arguments(
            "INSERT INTO VALUE (col1, col2, col3) VALUES ('a', 'b', 1)",
            "INSERT INTO VALUE (col1, col2, col3) VALUES (?, ?, ?)"),
        arguments(
            "INSERT INTO VALUE ( col1, col2, col3 ) VALUES ('a', 'b', 1)",
            "INSERT INTO VALUE ( col1, col2, col3 ) VALUES (?, ?, ?)"),
        arguments(
            "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff','罿潯罿潯罿潯罿潯罿潯',12983)",
            "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"),
        arguments(
            "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff','罿',12983)",
            "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"),
        arguments(
            "SELECT 3 AS NUCLEUS_TYPE,A0.ID,A0.\"NAME\" FROM \"VALUE\" A0",
            "SELECT ? AS NUCLEUS_TYPE,A0.ID,A0.\"NAME\" FROM \"VALUE\" A0"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > .9999",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > 0.9999",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > -0.9999",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > -1e6",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > +1e6",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > +255",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > +6.34F",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > +6f",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > +0.5D",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > -1d",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > x'ff'",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > X'ff'",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > 0xff",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 > ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ''",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ' '",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> '  '",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ' \\' '",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ' \\'Бегите, глупцы '",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ' x '",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ' x x'",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ' x\\'ab x'",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ' \\' 0xf '",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> '5,123'",
            "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where "
                + "col1 <> ?"),
        arguments(
            "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))",
            "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"),
        arguments(
            "CREATE TABLE S_H2 ( id INTEGER not NULL, PRIMARY KEY ( id ) )",
            "CREATE TABLE S_H2 ( id INTEGER not NULL, PRIMARY KEY ( id ) )"),
        arguments(
            "SELECT * FROM TABLE WHERE name = 'O''Brady'", "SELECT * FROM TABLE WHERE name = ?"),
        arguments(
            "INSERT INTO visits VALUES (2, 8, '2013-01-02', 'rabies shot')",
            "INSERT INTO visits VALUES (?, ?, ?, ?)"),
        arguments(
            "SELECT\n"
                + "\tcountry.country_name_eng,\n"
                + "\tSUM(CASE WHEN call.id IS NOT NULL THEN 1 ELSE 0 END) AS calls,\n"
                + "\tAVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),0)) AS "
                + "avg_difference\n"
                + "FROM country\n"
                + "LEFT JOIN city ON city.country_id = country.id\n"
                + "LEFT JOIN customer ON city.id = customer.city_id\n"
                + "LEFT JOIN call ON call.customer_id = customer.id\n"
                + "GROUP BY\n"
                + "\tcountry.id,\n"
                + "\tcountry.country_name_eng\n"
                + "HAVING AVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),0)) > "
                + "(SELECT AVG(DATEDIFF(SECOND, call.start_time, call.end_time)) FROM call)\n"
                + "ORDER BY calls DESC, country.id ASC;",
            "SELECT\n"
                + "\tcountry.country_name_eng,\n"
                + "\tSUM(CASE WHEN call.id IS NOT NULL THEN ? ELSE ? END) AS calls,\n"
                + "\tAVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),?)) AS "
                + "avg_difference\n"
                + "FROM country\n"
                + "LEFT JOIN city ON city.country_id = country.id\n"
                + "LEFT JOIN customer ON city.id = customer.city_id\n"
                + "LEFT JOIN call ON call.customer_id = customer.id\n"
                + "GROUP BY\n"
                + "\tcountry.id,\n"
                + "\tcountry.country_name_eng\n"
                + "HAVING AVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),?)) > "
                + "(SELECT AVG(DATEDIFF(SECOND, call.start_time, call.end_time)) FROM call)\n"
                + "ORDER BY calls DESC, country.id ASC;"),
        arguments(
            "DROP VIEW IF EXISTS v_country_all; GO CREATE VIEW v_country_all AS SELECT * "
                + "FROM country;",
            "DROP VIEW IF EXISTS v_country_all; GO CREATE VIEW v_country_all AS SELECT * "
                + "FROM country;"),
        arguments(
            "UPDATE v_country_all SET\n" + "  country_name = 'Nova1'\n" + "WHERE id = 8;",
            "UPDATE v_country_all SET\n" + "  country_name = ?\n" + "WHERE id = ?"),
        arguments(
            "UPDATE v_country_all /* 1. in-line comment */ SET\n"
                + "  /*\n"
                + "   * 2. multi-line comment\n"
                + "   */\n"
                + "  country_name = 'Nova1'\n"
                + "  -- 3. single-line comment\n"
                + "WHERE id = 8;",
            "UPDATE v_country_all /* ? in-line comment */ SET\n"
                + "  /*\n"
                + "   * ? multi-line comment\n"
                + "   */\n"
                + "  country_name = ?\n"
                + "  -- ? single-line comment\n"
                + "WHERE id = ?"),
        arguments(
            "INSERT INTO country (country_name, country_name_eng, country_code) VALUES "
                + "('Deutschland', 'Germany', 'DEU');\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES "
                + "('Srbija', 'Serbia', 'SRB');\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES "
                + "('Hrvatska', 'Croatia', 'HRV');\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES "
                + "('United States of America', 'United States of America', 'USA');\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES "
                + "('Polska', 'Poland', 'POL');",
            "INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, "
                + "?, ?);\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, "
                + "?, ?);\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, "
                + "?, ?);\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, "
                + "?, ?);\n"
                + "INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, "
                + "?, ?);"),
        arguments(
            "SELECT * FROM TABLE WHERE userId = ',' and foo=foo.bar",
            "SELECT * FROM TABLE WHERE userId = ? and foo=foo.bar"),
        arguments(
            "SELECT * FROM TABLE WHERE userId =     ','||foo.bar",
            "SELECT * FROM TABLE WHERE userId =     ?||foo.bar"),
        arguments("CLOSE \"<unnamed portal 1>\"", "CLOSE \"<unnamed portal 1>\""),
        arguments("CLOSE \"<unnamed portal 1>\\\"", "CLOSE \"<unnamed portal 1>\\\""),
        arguments("FETCH ALL IN \"<unnamed portal 1>\"", "FETCH ALL IN \"<unnamed portal 1>\""),
        arguments("SELECT 1 WHERE x = \"col 1\"", "SELECT ? WHERE x = \"col 1\""),
        arguments("SELECT \"x\\\", 123 FROM t", "SELECT \"x\\\", ? FROM t"),
        arguments(
            "SELECT count(*) AS totcount FROM (SELECT \"c1\", "
                + "\"c2\",\"c3\",\"c4\",\"c5\",\"c6\",\"c7\",\"c8\", \"c9\", \"c10\",\"c11\",\"c12\",\"c13\",\"c14\", "
                + "\"c15\",\"c16\",\"c17\",\"c18\", \"c19\",\"c20\",\"c21\",\"c22\",\"c23\", \"c24\",\"c25\",\"c26\", "
                + "\"c27\" FROM (SELECT bar.y AS \"c2\", foo.x AS \"c3\", foo.z AS \"c4\", DECODE(foo.a, "
                + "NULL,NULL, foo.a ||', '|| foo.b) AS \"c5\" , foo.c AS \"c6\", bar.d AS \"c1\", bar.e "
                + "AS \"c7\", bar.f AS \"c8\", bar.g AS \"c9\", "
                + "TO_DATE(TO_CHAR(TO_DATE(bar.h,'YYYYMMDD'),'DD-MON-YYYY'),'DD-MON-YYYY') AS "
                + "\"c10\", TO_DATE(TO_CHAR(TO_DATE(bar.i,'YYYYMMDD'),'DD-MON-YYYY'),'DD-MON-YYYY') "
                + "AS \"c11\", CASE WHEN DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 150 THEN '>150 Days' WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 120 THEN '121 to 150 "
                + "Days' WHEN DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 90 THEN '91 to 120 Days' WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 60 THEN '61 to 90 "
                + "Days' WHEN DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 30 THEN '31 to 60 Days' WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 0 THEN '1 to 30 "
                + "Days' ELSE NULL END AS \"c12\", DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD')),NULL) as \"c13\", bar.k AS \"c14\", bar.l ||', "
                + "'||bar.m AS \"c15\", DECODE(bar.n, NULL, NULL,bar.n ||', '||bar.o) AS \"c16\", "
                + "bar.p AS \"c17\", bar.q AS \"c18\", bar.r AS \"c19\", bar.s AS \"c20\", qux.a AS \"c21\", "
                + "TO_CHAR(TO_DATE(qux.b,'YYYYMMDD'),'DD-MON-YYYY') AS \"c22\", "
                + "DECODE(qux.l,NULL,NULL, qux.l ||', '||qux.m) AS \"c23\", bar.a AS \"c24\", "
                + "TO_CHAR(TO_DATE(bar.j,'YYYYMMDD'),'DD-MON-YYYY') AS \"c25\", DECODE(bar.c , "
                + "1,'N',0, 'Y', bar.c ) AS \"c26\", bar.y AS y, bar.d, bar.d AS \"c27\" FROM "
                + "blort.bar , ( SELECT * FROM (SELECT a,a,l,m,b,c, RANK() OVER (PARTITION BY c "
                + "ORDER BY b DESC) RNK FROM blort.d WHERE y IN (:protocols)) WHERE RNK = 1) qux, "
                + "blort.foo WHERE bar.c = qux.c(+) AND bar.x = foo.x AND bar.y IN (:protocols) "
                + "and bar.x IN (:sites)) ) SELECT count(*) AS totcount FROM (SELECT \"c1\", "
                + "\"c2\",\"c3\",\"c4\",\"c5\",\"c6\",\"c7\",\"c8\", \"c9\", \"c10\",\"c11\",\"c12\",\"c13\",\"c14\", "
                + "\"c15\",\"c16\",\"c17\",\"c18\", \"c19\",\"c20\",\"c21\",\"c22\",\"c23\", \"c24\",\"c25\",\"c26\", "
                + "\"c27\" FROM (SELECT bar.y AS \"c2\", foo.x AS \"c3\", foo.z AS \"c4\", DECODE(foo.a, "
                + "NULL,NULL, foo.a ||', '|| foo.b) AS \"c5\" , foo.c AS \"c6\", bar.d AS \"c1\", bar.e "
                + "AS \"c7\", bar.f AS \"c8\", bar.g AS \"c9\", "
                + "TO_DATE(TO_CHAR(TO_DATE(bar.h,'YYYYMMDD'),'DD-MON-YYYY'),'DD-MON-YYYY') AS "
                + "\"c10\", TO_DATE(TO_CHAR(TO_DATE(bar.i,'YYYYMMDD'),'DD-MON-YYYY'),'DD-MON-YYYY') "
                + "AS \"c11\", CASE WHEN DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 150 THEN '>150 Days' WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 120 THEN '121 to 150 "
                + "Days' WHEN DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 90 THEN '91 to 120 Days' WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 60 THEN '61 to 90 "
                + "Days' WHEN DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 30 THEN '31 to 60 Days' WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,'YYYYMMDD'))) > 0 THEN '1 to 30 "
                + "Days' ELSE NULL END AS \"c12\", DECODE(bar.j, NULL, TRUNC(SYSDATE) - "
                + "TRUNC(TO_DATE(bar.h,'YYYYMMDD')),NULL) as \"c13\", bar.k AS \"c14\", bar.l ||', "
                + "'||bar.m AS \"c15\", DECODE(bar.n, NULL, NULL,bar.n ||', '||bar.o) AS \"c16\", "
                + "bar.p AS \"c17\", bar.q AS \"c18\", bar.r AS \"c19\", bar.s AS \"c20\", qux.a AS \"c21\", "
                + "TO_CHAR(TO_DATE(qux.b,'YYYYMMDD'),'DD-MON-YYYY') AS \"c22\", "
                + "DECODE(qux.l,NULL,NULL, qux.l ||', '||qux.m) AS \"c23\", bar.a AS \"c24\", "
                + "TO_CHAR(TO_DATE(bar.j,'YYYYMMDD'),'DD-MON-YYYY') AS \"c25\", DECODE(bar.c , "
                + "1,'N',0, 'Y', bar.c ) AS \"c26\", bar.y AS y, bar.d, bar.d AS \"c27\" FROM "
                + "blort.bar , ( SELECT * FROM (SELECT a,a,l,m,b,c, RANK() OVER (PARTITION BY c "
                + "ORDER BY b DESC) RNK FROM blort.d WHERE y IN (:protocols)) WHERE RNK = 1) qux, "
                + "blort.foo WHERE bar.c = qux.c(+) AND bar.x = foo.x AND bar.y IN (:protocols) "
                + "and bar.x IN (:sites)) )",
            "SELECT count(*) AS totcount FROM (SELECT \"c1\", "
                + "\"c2\",\"c3\",\"c4\",\"c5\",\"c6\",\"c7\",\"c8\", \"c9\", \"c10\",\"c11\",\"c12\",\"c13\",\"c14\", "
                + "\"c15\",\"c16\",\"c17\",\"c18\", \"c19\",\"c20\",\"c21\",\"c22\",\"c23\", \"c24\",\"c25\",\"c26\", "
                + "\"c27\" FROM (SELECT bar.y AS \"c2\", foo.x AS \"c3\", foo.z AS \"c4\", DECODE(foo.a, "
                + "NULL,NULL, foo.a ||?|| foo.b) AS \"c5\" , foo.c AS \"c6\", bar.d AS \"c1\", bar.e AS "
                + "\"c7\", bar.f AS \"c8\", bar.g AS \"c9\", TO_DATE(TO_CHAR(TO_DATE(bar.h,?),?),?) AS "
                + "\"c10\", TO_DATE(TO_CHAR(TO_DATE(bar.i,?),?),?) AS \"c11\", CASE WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? ELSE NULL END AS "
                + "\"c12\", DECODE(bar.j, NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?)),NULL) as "
                + "\"c13\", bar.k AS \"c14\", bar.l ||?||bar.m AS \"c15\", DECODE(bar.n, NULL, "
                + "NULL,bar.n ||?||bar.o) AS \"c16\", bar.p AS \"c17\", bar.q AS \"c18\", bar.r AS "
                + "\"c19\", bar.s AS \"c20\", qux.a AS \"c21\", TO_CHAR(TO_DATE(qux.b,?),?) AS \"c22\", "
                + "DECODE(qux.l,NULL,NULL, qux.l ||?||qux.m) AS \"c23\", bar.a AS \"c24\", "
                + "TO_CHAR(TO_DATE(bar.j,?),?) AS \"c25\", DECODE(bar.c , ?,?,?, ?, bar.c ) AS "
                + "\"c26\", bar.y AS y, bar.d, bar.d AS \"c27\" FROM blort.bar , ( SELECT * FROM "
                + "(SELECT a,a,l,m,b,c, RANK() OVER (PARTITION BY c ORDER BY b DESC) RNK FROM "
                + "blort.d WHERE y IN (:protocols)) WHERE RNK = ?) qux, blort.foo WHERE bar.c = "
                + "qux.c(+) AND bar.x = foo.x AND bar.y IN (:protocols) and bar.x IN (:sites)) ) "
                + "SELECT count(*) AS totcount FROM (SELECT \"c1\", "
                + "\"c2\",\"c3\",\"c4\",\"c5\",\"c6\",\"c7\",\"c8\", \"c9\", \"c10\",\"c11\",\"c12\",\"c13\",\"c14\", "
                + "\"c15\",\"c16\",\"c17\",\"c18\", \"c19\",\"c20\",\"c21\",\"c22\",\"c23\", \"c24\",\"c25\",\"c26\", "
                + "\"c27\" FROM (SELECT bar.y AS \"c2\", foo.x AS \"c3\", foo.z AS \"c4\", DECODE(foo.a, "
                + "NULL,NULL, foo.a ||?|| foo.b) AS \"c5\" , foo.c AS \"c6\", bar.d AS \"c1\", bar.e AS "
                + "\"c7\", bar.f AS \"c8\", bar.g AS \"c9\", TO_DATE(TO_CHAR(TO_DATE(bar.h,?),?),?) AS "
                + "\"c10\", TO_DATE(TO_CHAR(TO_DATE(bar.i,?),?),?) AS \"c11\", CASE WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? WHEN DECODE(bar.j, "
                + "NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?))) > ? THEN ? ELSE NULL END AS "
                + "\"c12\", DECODE(bar.j, NULL, TRUNC(SYSDATE) - TRUNC(TO_DATE(bar.h,?)),NULL) as "
                + "\"c13\", bar.k AS \"c14\", bar.l ||?||bar.m AS \"c15\", DECODE(bar.n, NULL, "
                + "NULL,bar.n ||?||bar.o) AS \"c16\", bar.p AS \"c17\", bar.q AS \"c18\", bar.r AS "
                + "\"c19\", bar.s AS \"c20\", qux.a AS \"c21\", TO_CHAR(TO_DATE(qux.b,?),?) AS \"c22\", "
                + "DECODE(qux.l,NULL,NULL, qux.l ||?||qux.m) AS \"c23\", bar.a AS \"c24\", "
                + "TO_CHAR(TO_DATE(bar.j,?),?) AS \"c25\", DECODE(bar.c , ?,?,?, ?, bar.c ) AS "
                + "\"c26\", bar.y AS y, bar.d, bar.d AS \"c27\" FROM blort.bar , ( SELECT * FROM "
                + "(SELECT a,a,l,m,b,c, RANK() OVER (PARTITION BY c ORDER BY b DESC) RNK FROM "
                + "blort.d WHERE y IN (:protocols)) WHERE RNK = ?) qux, blort.foo WHERE bar.c = "
                + "qux.c(+) AND bar.x = foo.x AND bar.y IN (:protocols) and bar.x IN (:sites)) )"));
  }
}
