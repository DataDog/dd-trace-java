package datadog.trace.api.normalize

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification

class SQLNormalizerTest extends DDSpecification {

  def "test normalize #sql"() {
    when:
    UTF8BytesString normalized = SQLNormalizer.normalize(sql)
    then:
    normalized as String == expected
    where:
    sql                                                                                                                              | expected
    ""                                                                                                                               | ""
    "   "                                                                                                                            | "   "
    "         "                                                                                                                      | "         "
    "罿"                                                                                                                              | "罿"
    "罿潯"                                                                                                                             | "罿潯"
    "罿潯罿潯罿潯罿潯罿潯"                                                                                                                     | "罿潯罿潯罿潯罿潯罿潯"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964'"                                                                             | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681964'"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964'"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964\\''"                                                                          | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = '\\'abc1287681964\\''"                                                                       | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287681\\'964'"                                                                       | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'964'"                                                                    | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964'"                                                           | "SELECT * FROM TABLE WHERE userId = ?"
    "SELECT * FROM TABLE WHERE userId IN (\'a\', \'b\', \'c\')"                                                                      | "SELECT * FROM TABLE WHERE userId IN (?, ?, ?)"
    "SELECT * FROM TABLE WHERE userId IN (\'abc\\'1287681\\'964\', \'abc\\'1287\\'681\\'\\'\\'\\'964\', \'abc\\'1287\\'681\\'964\')" | "SELECT * FROM TABLE WHERE userId IN (?, ?, ?)"
    "SELECT * FROM TABLE WHERE userId = 'abc1287681964' ORDER BY FOO DESC"                                                           | "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"
    "SELECT * FROM TABLE WHERE userId = 'abc\\'1287\\'681\\'\\'\\'\\'964' ORDER BY FOO DESC"                                         | "SELECT * FROM TABLE WHERE userId = ? ORDER BY FOO DESC"
    "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar"                                                                | "SELECT * FROM TABLE JOIN SOMETHING ON TABLE.foo = SOMETHING.bar"
    "CREATE TABLE \"VALUE\""                                                                                                         | "CREATE TABLE \"VALUE\""
    "INSERT INTO \"VALUE\" (\"column\") VALUES (\'ljahklshdlKASH\')"                                                                 | "INSERT INTO \"VALUE\" (\"column\") VALUES (?)"
    "INSERT INTO \"VALUE\" (\"col1\",\"col2\",\"col3\") VALUES (\'blah\',12983,X'ff')"                                               | "INSERT INTO \"VALUE\" (\"col1\",\"col2\",\"col3\") VALUES (?,?,?)"
    "INSERT INTO \"VALUE\" (\"col1\", \"col2\", \"col3\") VALUES (\'blah\',12983,X'ff')"                                             | "INSERT INTO \"VALUE\" (\"col1\", \"col2\", \"col3\") VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (\'blah\',12983,X'ff')"                                                               | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (12983,X'ff',\'blah\')"                                                               | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff',\'blah\',12983)"                                                               | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES ('a',\'b\',1)"                                                                        | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1, col2, col3) VALUES ('a',\'b\',1)"                                                                      | "INSERT INTO VALUE (col1, col2, col3) VALUES (?,?,?)"
    "INSERT INTO VALUE ( col1, col2, col3 ) VALUES ('a',\'b\',1)"                                                                    | "INSERT INTO VALUE ( col1, col2, col3 ) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES ('a', \'b\' ,1)"                                                                      | "INSERT INTO VALUE (col1,col2,col3) VALUES (?, ? ,?)"
    "INSERT INTO VALUE (col1, col2, col3) VALUES ('a', \'b\', 1)"                                                                    | "INSERT INTO VALUE (col1, col2, col3) VALUES (?, ?, ?)"
    "INSERT INTO VALUE ( col1, col2, col3 ) VALUES ('a', \'b\', 1)"                                                                  | "INSERT INTO VALUE ( col1, col2, col3 ) VALUES (?, ?, ?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff',\'罿潯罿潯罿潯罿潯罿潯\',12983)"                                                         | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "INSERT INTO VALUE (col1,col2,col3) VALUES (X'ff',\'罿\',12983)"                                                                  | "INSERT INTO VALUE (col1,col2,col3) VALUES (?,?,?)"
    "SELECT 3 AS NUCLEUS_TYPE,A0.ID,A0.\"NAME\" FROM \"VALUE\" A0"                                                                   | "SELECT ? AS NUCLEUS_TYPE,A0.ID,A0.\"NAME\" FROM \"VALUE\" A0"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > .9999"                                      | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > 0.9999"                                     | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > -0.9999"                                    | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > -1e6"                                       | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > +1e6"                                       | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > +255"                                       | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > +6.34F"                                     | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > +6f"                                        | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > +0.5D"                                      | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > -1d"                                        | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > x'ff'"                                      | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > X'ff'"                                      | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > 0xff"                                       | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 > ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \'\'"                                      | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \' \'"                                     | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \'  \'"                                    | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \' \\\' \'"                                | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \' \\\'Бегите, глупцы \'"                  | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \' x \'"                                   | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \' x x\'"                                  | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \' x\\\'ab x\'"                            | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \' \\\' 0xf \'"                            | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> \'5,123\'"                                 | "SELECT COUNT(*) FROM TABLE_1 JOIN table_2 ON TABLE_1.foo = table_2.bar where col1 <> ?"
    "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"                                                                    | "CREATE TABLE S_H2 (id INTEGER not NULL, PRIMARY KEY ( id ))"
    "CREATE TABLE S_H2 ( id INTEGER not NULL, PRIMARY KEY ( id ) )"                                                                  | "CREATE TABLE S_H2 ( id INTEGER not NULL, PRIMARY KEY ( id ) )"
    "SELECT * FROM TABLE WHERE name = 'O''Brady'"                                                                                    | "SELECT * FROM TABLE WHERE name = ?"
    "INSERT INTO visits VALUES (2, 8, '2013-01-02', 'rabies shot')"                                                                  | "INSERT INTO visits VALUES (?, ?, ?, ?)"
    """SELECT
\tcountry.country_name_eng,
\tSUM(CASE WHEN call.id IS NOT NULL THEN 1 ELSE 0 END) AS calls,
\tAVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),0)) AS avg_difference
FROM country
LEFT JOIN city ON city.country_id = country.id
LEFT JOIN customer ON city.id = customer.city_id
LEFT JOIN call ON call.customer_id = customer.id
GROUP BY
\tcountry.id,
\tcountry.country_name_eng
HAVING AVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),0)) > (SELECT AVG(DATEDIFF(SECOND, call.start_time, call.end_time)) FROM call)
ORDER BY calls DESC, country.id ASC;"""                                                                          | """SELECT
\tcountry.country_name_eng,
\tSUM(CASE WHEN call.id IS NOT NULL THEN ? ELSE ? END) AS calls,
\tAVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),?)) AS avg_difference
FROM country
LEFT JOIN city ON city.country_id = country.id
LEFT JOIN customer ON city.id = customer.city_id
LEFT JOIN call ON call.customer_id = customer.id
GROUP BY
\tcountry.id,
\tcountry.country_name_eng
HAVING AVG(ISNULL(DATEDIFF(SECOND, call.start_time, call.end_time),?)) > (SELECT AVG(DATEDIFF(SECOND, call.start_time, call.end_time)) FROM call)
ORDER BY calls DESC, country.id ASC;"""
    """DROP VIEW IF EXISTS v_country_all; GO CREATE VIEW v_country_all AS SELECT * FROM country;"""              | """DROP VIEW IF EXISTS v_country_all; GO CREATE VIEW v_country_all AS SELECT * FROM country;"""
    """UPDATE v_country_all SET
  country_name = 'Nova1'
WHERE id = 8;"""                                                                                          | """UPDATE v_country_all SET
  country_name = ?
WHERE id = ?"""
    """INSERT INTO country (country_name, country_name_eng, country_code) VALUES ('Deutschland', 'Germany', 'DEU');
INSERT INTO country (country_name, country_name_eng, country_code) VALUES ('Srbija', 'Serbia', 'SRB');
INSERT INTO country (country_name, country_name_eng, country_code) VALUES ('Hrvatska', 'Croatia', 'HRV');
INSERT INTO country (country_name, country_name_eng, country_code) VALUES ('United States of America', 'United States of America', 'USA');
INSERT INTO country (country_name, country_name_eng, country_code) VALUES ('Polska', 'Poland', 'POL');""" | """INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, ?, ?);
INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, ?, ?);
INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, ?, ?);
INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, ?, ?);
INSERT INTO country (country_name, country_name_eng, country_code) VALUES (?, ?, ?);"""
  }
}
