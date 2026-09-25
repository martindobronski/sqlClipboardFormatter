package signaliduna;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests fuer den sql-formatter-Pfad.
 *
 * <p>Bewusst Property- statt Snapshot-Tests: es wird nicht auf exakte
 * Leerzeichen geprueft, sondern darauf, was der Formatierer einruegt, was er
 * gross schreibt und was er <em>nicht</em> anfasst. So bleibt die Suite
 * stabil, wenn die Library geupgraded wird, und faellt trotzdem bei einem
 * echten Regelregress.
 */
class SqlPrettyFormatterTest {

    private static String format(String sql) {
        return SqlPrettyFormatter.format(sql, SqlDialect.STANDARD).sql();
    }

    private static SqlPrettyFormatter.Result result(String sql, SqlDialect dialect) {
        return SqlPrettyFormatter.format(sql, dialect);
    }

    @Nested
    @DisplayName("Best-Practice-Layout")
    class Layout {

        @Test
        @DisplayName("Klauseln stehen in eigenen Zeilen, Listen untereinander")
        void klauseln_in_eigenen_zeilen() {
            String out = format("select id, name from users where active = 1");

            assertEquals("""
                    SELECT
                        id,
                        name
                    FROM
                        users
                    WHERE
                        active = 1""", out);
        }

        @Test
        @DisplayName("fuehrende Keyword-Schreibweise und Satzzeichen werden normalisiert")
        void korrigiert_abstaende_und_case() {
            // Vorher: "SELECT id ,name FROM" - Wortluecke vor dem Komma blieb stehen.
            assertEquals("""
                    SELECT
                        id,
                        name
                    FROM
                        users""", format("select  id ,name from  users"));
        }

        @Test
        @DisplayName("Unterabfragen werden eingerueckt")
        void unterabfrage_eingerueckt() {
            String out = format("select * from (select x from t where a=1) sub");

            String[] lines = out.split("\n");
            int outer = -1;
            int inner = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("SELECT") && outer < 0) {
                    outer = i;
                } else if (lines[i].contains("SELECT") && outer >= 0 && inner < 0) {
                    inner = i;
                }
            }

            assertTrue(outer >= 0 && inner > outer, "keine zweite SELECT-Zeile in:\n" + out);
            int outerIndent = lines[outer].length() - lines[outer].stripLeading().length();
            int innerIndent = lines[inner].length() - lines[inner].stripLeading().length();
            assertTrue(innerIndent > outerIndent,
                    "Unterabfrage nicht tiefer eingerueckt (" + innerIndent + " <= " + outerIndent + "):\n" + out);
        }

        @Test
        void leerer_und_null_eingang() {
            assertEquals("", SqlPrettyFormatter.format("", SqlDialect.STANDARD).sql());
            assertEquals("", SqlPrettyFormatter.format(null, SqlDialect.STANDARD).sql());
            assertEquals("", SqlPrettyFormatter.format("   \n ", null).sql());
        }
    }

    @Nested
    @DisplayName("Inhalt wird nicht angetastet")
    class Inhalt {

        @Test
        @DisplayName("String-Literale bleiben woertlich, Keywords darin nicht grossgeschrieben")
        void literal_bleibt_woertlich() {
            String out = format("select 'please  select   from the menu' as x from t");

            assertTrue(out.contains("'please  select   from the menu'"),
                    "Literal veraendert: " + out);
        }

        @Test
        @DisplayName("Zeilenkommentar mit Apostroph bleibt unangetastet")
        void kommentar_mit_apostroph_bleibt_woertlich() {
            String out = format("select 1 -- it's a note, don't touch\nfrom t");

            assertTrue(out.contains("-- it's a note, don't touch"),
                    "Kommentar veraendert: " + out);
        }

        @Test
        @DisplayName("quotierte Bezeichner behalten ihren Inhalt")
        void bezeichner_bleibt_woertlich() {
            String out = format("select \"my  col\", [other  col] from t");

            assertTrue(out.contains("my  col"), "Bezeichner veraendert: " + out);
            assertTrue(out.contains("other"), "Bezeichner verloren: " + out);
        }
    }

    @Nested
    @DisplayName("Sicherheits-Routing")
    class Routing {

        @Test
        @DisplayName("$$-Quoting schaltet selbsttaetig auf PostgreSQL und bleibt woertlich")
        void dollar_quote_waehlt_postgresql() {
            String sql = "create function f() returns int as $$ select 1 from dual $$ language sql;";
            SqlPrettyFormatter.Result r = result(sql, SqlDialect.STANDARD);

            assertTrue(r.bestPractice(), "sollte den Library-Pfad nehmen");
            assertTrue(r.notice() != null && r.notice().contains("PostgreSQL"),
                    "Hinweis auf Dialektwechsel fehlt: " + r.notice());
            // Der Koerper darf nicht formatiert worden sein.
            assertTrue(r.sql().contains("$$ select 1 from dual $$"),
                    "Funktionskoerper veraendert: " + r.sql());
        }

        @Test
        @DisplayName("$$-Quoting wird auch unter PostgreSQL nicht angefasst")
        void dollar_quote_unter_postgresql() {
            String sql = "create function f() returns int as $$ select 1 $$ language sql;";
            SqlPrettyFormatter.Result r = result(sql, SqlDialect.POSTGRESQL);

            assertTrue(r.bestPractice());
            assertNull(r.notice(), "kein Hinweis noetig, war: " + r.notice());
            assertTrue(r.sql().contains("$$ select 1 $$"), "Koerper veraendert: " + r.sql());
        }

        @Test
        @DisplayName("getaggte Dollar-Tags schliessen nicht")
        void dollar_tag_muss_uebereinstimmen() {
            // $a$ ... $b$ ist kein geschlossenes Quoting.
            String out = SqlPrettyFormatter.format("select $a$ 1 $b$", SqlDialect.POSTGRESQL).sql();
            assertFalse(out.isBlank());
        }

        @Test
        @DisplayName("E'-Strings fallen auf die konservative Normalisierung zurueck")
        void escape_string_faellt_zurueck() {
            SqlPrettyFormatter.Result r =
                    result("select E'it\\'s ok' from t", SqlDialect.POSTGRESQL);

            assertFalse(r.bestPractice(), "darf die Library nicht nutzen");
            assertTrue(r.notice() != null, "meldet keinen Rueckfall");
            assertTrue(r.sql().contains("E'it\\'s ok'"), "Inhalt verloren: " + r.sql());
        }

        @Test
        @DisplayName("DATE'-Literale fallen zurueck - sonst entstuende ungueltiges SQL")
        void typed_literal_faellt_zurueck() {
            SqlPrettyFormatter.Result r =
                    result("select * from t where d = date'2020-01-01'", SqlDialect.POSTGRESQL);

            assertFalse(r.bestPractice(), "darf die Library nicht nutzen");
            assertTrue(r.sql().contains("date'2020-01-01'"),
                    "Trennung wuerde SQL zerstoeren: " + r.sql());
        }

        @Test
        @DisplayName("Bezeichner, die auf E enden, loesen keinen Rueckfall aus")
        void bezeichner_auf_e_ist_kein_typisiertes_literal() {
            SqlPrettyFormatter.Result r =
                    result("select * from t where some_e'x' = 1", SqlDialect.POSTGRESQL);

            assertTrue(r.bestPractice(), "sollte die Library nutzen, Hinweis: " + r.notice());
        }

        @Test
        void plpgsql_parameter_ist_kein_dollar_quoting() {
            SqlPrettyFormatter.Result r =
                    result("create function f() returns int as $$ begin return $1; end $$ language plpgsql;",
                            SqlDialect.POSTGRESQL);

            assertTrue(r.bestPractice());
            assertTrue(r.sql().contains("$$ begin return $1; end $$"),
                    "PL/pgSQL-Rumpf veraendert: " + r.sql());
        }
    }

    @Nested
    @DisplayName("Dialect-Auswahl")
    class Dialekte {

        @Test
        void jeder_dialect_formatiert() {
            for (SqlDialect dialect : SqlDialect.values()) {
                SqlPrettyFormatter.Result r = result("select a from t where b=1", dialect);
                assertTrue(r.bestPractice(), dialect + " sollte den Library-Pfad nehmen");
                assertTrue(r.sql().contains("SELECT"), dialect + ": " + r.sql());
            }
        }

        @Test
        void standard_ist_voreingestellt() {
            assertEquals(SqlDialect.STANDARD, SqlDialect.values()[0],
                    "Standard SQL sollte der erste Listeneintrag sein");
        }
    }

    @Nested
    @DisplayName("Operatoren werden nicht zerschnitten")
    class Operatoren {

        @Test
        @DisplayName("Leerraum neben einem Operator ist erlaubt")
        void leerraum_neben_operator() {
            assertNull(SqlPrettyFormatter.alteredOperator("a = b", "a=b"));
            assertNull(SqlPrettyFormatter.alteredOperator("a=-1", "a = -1"));
        }

        @Test
        @DisplayName("Leerraum innerhalb eines Operators ist ein Bruch")
        void leerraum_in_operator_ist_bruch() {
            // Genau hier liegt der Unterschied: #>> darf nicht wie #>> aussehen.
            assertEquals("#>>", SqlPrettyFormatter.alteredOperator("a #>> 'k'", "a # > > 'k'"));
        }

        @Test
        void gleichbleibende_operatoren() {
            assertNull(SqlPrettyFormatter.alteredOperator("a || b", "a || b"));
            assertNull(SqlPrettyFormatter.alteredOperator("a::text", "a :: TEXT"));
        }

        @Test
        @DisplayName("hinzugekommene oder weggefallene Operatorzeichen")
        void zeichen_geaendert() {
            // "||" zerschnitten: die Zeichen sind dieselben, nur mit Leerraum.
            assertEquals("||", SqlPrettyFormatter.alteredOperator("a || b", "a | | b"));
            // Hier aendert sich die Zeichenfolge selbst.
            assertEquals("<geaendert>", SqlPrettyFormatter.alteredOperator("a = b", "a || b"));
            assertEquals("<geaendert>", SqlPrettyFormatter.alteredOperator("a + b", "a b"));
        }

        @Test
        @DisplayName("zwei nebeneinanderstehende Einzeloperatoren sind kein Bruch")
        void juxtapozierte_einzeloperatoren() {
            // "a=-1" enthaelt die Zeichenfolge "=-", die Ausgabe "a = -1" die
            // Zeichenfolge "=-" ebenfalls. Beide duerfen sich also frei trennen.
            assertNull(SqlPrettyFormatter.alteredOperator("a=-1", "a = -1"));
            assertNull(SqlPrettyFormatter.alteredOperator("a>=1", "a >= 1"));
        }

        @Test
        @DisplayName("|| bleibt unter PostgreSQL und T-SQL erhalten")
        void verkkettung_je_dialect() {
            String sql = "select a || 'x' from t";
            for (SqlDialect dialect : new SqlDialect[]{SqlDialect.POSTGRESQL, SqlDialect.TSQL}) {
                SqlPrettyFormatter.Result r = result(sql, dialect);
                assertFalse(r.bestPractice(), dialect + " muss zurueckfallen");
                assertTrue(r.sql().contains("a || 'x'"), dialect + ": " + r.sql());
            }
            for (SqlDialect dialect : new SqlDialect[]{SqlDialect.STANDARD, SqlDialect.MYSQL, SqlDialect.DB2}) {
                assertTrue(result(sql, dialect).bestPractice(), dialect + " kann die Library nutzen");
            }
        }

        @Test
        @DisplayName("JSON-Operator #>> bleibt auch im Standard-Dialekt erhalten")
        void json_operator_im_standard() {
            SqlPrettyFormatter.Result r =
                    result("select * from t where a = b #>> '{k}'", SqlDialect.STANDARD);
            assertFalse(r.bestPractice(), "der Standard-Dialekt zerschnidet #>>");
            assertTrue(r.sql().contains("#>>"), "Operator zerstoert: " + r.sql());
        }

        @Test
        @DisplayName(":: bleibt unter PL/SQL und DB2 erhalten")
        void cast_operator() {
            for (SqlDialect dialect : new SqlDialect[]{SqlDialect.PLSQL, SqlDialect.DB2}) {
                SqlPrettyFormatter.Result r = result("select a::text from t", dialect);
                assertFalse(r.bestPractice(), dialect + " muss zurueckfallen");
                assertTrue(r.sql().contains("a::text"), dialect + ": " + r.sql());
            }
        }

        @Test
        @DisplayName("gewoehnliche Abfragen loesen keinen Rueckfall aus")
        void gewoehnliche_abfragen_stay() {
            for (String sql : new String[]{
                    "select id, name from users where active = 1",
                    "select * from t where a <= 10 and b >= 2",
                    "select 1 where 1 = 1 except select 2",
                    "update t set a = a + 1, b = b * 2 where id = 3",
                    "select count(*) from t group by a having count(*) > 1",
            }) {
                assertTrue(result(sql, SqlDialect.STANDARD).bestPractice(), "Rueckfall bei: " + sql);
            }
        }
    }

    @Nested
    @DisplayName("Ehrliche Rueckmeldung zum Dialekt")
    class DialektRueckmeldung {

        @Test
        @DisplayName("wirksamer Dialekt wird beim Text erkannt und gemeldet")
        void wirkung_wird_gemeldet() {
            SqlPrettyFormatter.Result r = result("select a || 'x' from t", SqlDialect.PLSQL);
            assertTrue(r.sql().contains("A ||"), "PL/SQL schreibt Bezeichner gross: " + r.sql());
            assertTrue(r.notice() == null, "kein Hinweis noetig, war: " + r.notice());
        }

        @Test
        @DisplayName("wirkungsloser Dialekt wird ausdruecklich gesagt")
        void wirkungslos_wird_gemeldet() {
            // MySQL liefert hier exakt dasselbe wie Standard SQL. Das ist der
            // Fall, der beim Umschalten sonst einfach verwirrt.
            SqlPrettyFormatter.Result r = result("select id, name from users", SqlDialect.MYSQL);
            assertTrue(r.bestPractice());
            assertTrue(r.notice() != null && r.notice().contains("aendert diesen Text nicht"),
                    "kein Hinweis auf die Wirkungslosigkeit: " + r.notice());
        }

        @Test
        @DisplayName("beim Standard selbst gibt es keinen solchen Hinweis")
        void standard_ist_still() {
            assertNull(result("select id, name from users", SqlDialect.STANDARD).notice());
        }
    }

    @Nested
    @DisplayName("Backtick-Bezeichner")
    class Backticks {

        @Test
        @DisplayName("erkannt und auf MySQL umgeschaltet")
        void backtick_schaltet_um() {
            var r = result("select `col` from `tab` where `a b` = 1", SqlDialect.STANDARD);
            assertTrue(r.sql().contains("`col`"), "Bezeichner veraendert: " + r.sql());
            assertTrue(r.sql().contains("`a b`"), "Leerzeichen im Namen: " + r.sql());
            assertTrue(r.notice() != null && r.notice().contains("MySQL"),
                    "kein Umschalt-Hinweis: " + r.notice());
        }

        @Test
        @DisplayName("Leerzeichen im Bezeichner waeren ein anderer Name")
        void leerzeichen_waeren_fatal() {
            // Der Grund fuer den Guard: MySQL kennt ` col ` nicht als `col`.
            assertFalse(result("select `col` from `tab`", SqlDialect.STANDARD).sql().contains("` col `"),
                    "Leerzeichen im Backtick-Bezeichner");
        }

        @Test
        @DisplayName("Backtick im Kommentar zaehlt nicht")
        void backtick_im_kommentar() {
            assertFalse(SqlPrettyFormatter.hasBacktickIdentifier("select 1 -- niemals `x` benutzen"));
            assertFalse(SqlPrettyFormatter.hasBacktickIdentifier("select 1 /* ` auch hier nicht */"));
        }

        @Test
        @DisplayName("Backtick im Literal zaehlt nicht")
        void backtick_im_literal() {
            assertFalse(SqlPrettyFormatter.hasBacktickIdentifier("select 'ein `zeichen`' from t"));
            assertFalse(SqlPrettyFormatter.hasBacktickIdentifier("select \"ein `zeichen`\" from t"));
        }

        @Test
        @DisplayName("unterminiertes Literal verschluckt den Rest")
        void unterminiertes_literal() {
            assertFalse(SqlPrettyFormatter.hasBacktickIdentifier("select 'offen -- und `backtick"));
            assertFalse(SqlPrettyFormatter.hasBacktickIdentifier("select 'offen /* und `backtick"));
        }

        @Test
        @DisplayName("echter Bezeichner wird auch nach einem Kommentar erkannt")
        void bezeichner_nach_kommentar() {
            assertTrue(SqlPrettyFormatter.hasBacktickIdentifier("select 1 -- weg\n`col`"));
        }
    }

    @Nested
    @DisplayName("Welche Dialekte ueberhaupt wirken")
    class WirksameDialekte {

        @Test
        @DisplayName("Standard SQL genuegt fuer gewöhnliches SELECT")
        void standard_genuegt() {
            assertEquals(List.of(), SqlPrettyFormatter.effectiveDialects("select 1"));
            assertEquals(List.of(), SqlPrettyFormatter.effectiveDialects("insert into t values (1)"));
        }

        @Test
        @DisplayName("leerer Text hat keine wirksamen Dialekte")
        void leerer_text() {
            assertEquals(List.of(), SqlPrettyFormatter.effectiveDialects(""));
            assertEquals(List.of(), SqlPrettyFormatter.effectiveDialects("   \n "));
            assertEquals(List.of(), SqlPrettyFormatter.effectiveDialects(null));
        }

        @Test
        @DisplayName("TOP gehoert zu T-SQL und sonst niemandem")
        void top_ist_tsql() {
            assertEquals(List.of(SqlDialect.TSQL),
                    SqlPrettyFormatter.effectiveDialects("select top 10 id from t"));
        }

        @Test
        @DisplayName("PL/SQL schreibt Bezeichner gross")
        void plsql_gross() {
            assertTrue(SqlPrettyFormatter.effectiveDialects("select a, b from t").contains(SqlDialect.PLSQL));
        }

        @Test
        @DisplayName("die Liste folgt dem Text, nicht dem Dialekt")
        void liste_ist_textabhaengig() {
            // Dasselbe Programm, einmal mit und einmal ohne TOP - zwei
            // voellig verschiedene Antworten. Genau deshalb laesst sich die
            // Auswahl nicht statisch auf die wirksamen Dialekte verkleinern.
            assertEquals(List.of(SqlDialect.TSQL),
                    SqlPrettyFormatter.effectiveDialects("select top 10 id from t"));
            assertEquals(List.of(), SqlPrettyFormatter.effectiveDialects("select id from t"));
        }

        @Test
        @DisplayName("jeder gemeldete Dialekt liefert wirklich etwas anderes")
        void jede_meldung_stimmt() {
            for (String sql : new String[]{
                    "select top 10 id from t",
                    "select a || 'x' from t",
                    "select a, b from t where c = 1",
                    "select `col` from `tab`",
                    "select a::text from t",
            }) {
                String referenz = SqlPrettyFormatter.format(sql, SqlDialect.STANDARD).sql();
                for (SqlDialect wirksam : SqlPrettyFormatter.effectiveDialects(sql)) {
                    assertFalse(SqlPrettyFormatter.format(sql, wirksam).sql().equals(referenz),
                            wirksam + " wurde gemeldet, liefert aber dasselbe: " + sql);
                }
            }
        }
    }

    @Test
    @DisplayName("zweimal Formatieren aendert nichts")
    void idempotent() {
        for (String sql : new String[]{
                "select id, name from users where active = 1 order by name desc",
                "select * from (select a from t) x where y = 'a  b'",
                "select 1 -- don't touch\nfrom t",
                "create function f() returns int as $$ select 1 $$ language sql;",
        }) {
            String once = SqlPrettyFormatter.format(sql, SqlDialect.POSTGRESQL).sql();
            String twice = SqlPrettyFormatter.format(once, SqlDialect.POSTGRESQL).sql();
            assertEquals(once, twice, "nicht idempotent fuer: " + sql);
        }
    }
}
