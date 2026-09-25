package signaliduna;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressionstests fuer {@link SqlFormatService}.
 *
 * <p>Die Faelle mit "Literal" und "Bezeichner" waren vor dem Umbau echte Datenverluste:
 * die alte Regex-Ersetzung lief durch String-Literale und quotierte Spaltennamen hindurch.
 */
class SqlFormatServiceTest {

    @Nested
    @DisplayName("String-Literale bleiben unangetastet")
    class Literals {

        @Test
        void literal_inhalt_bleibt_woertlich() {
            assertEquals(
                    "WHERE name = 'please  select   from the menu' AND id = 1",
                    SqlFormatService.format("where name = 'please  select   from the menu' and id = 1"));
        }

        @Test
        void and_zeile_wird_eingerueckt_literal_bleibt_erhalten() {
            assertEquals("""
                    WHERE name = 'a  select'
                        AND id = 1""", SqlFormatService.format("where name = 'a  select'\nand id = 1"));
        }

        @Test
        void verdoppeltes_escapezeichen_oeffnet_kein_neues_literal() {
            assertEquals("WHERE a = 'it''s a  test'", SqlFormatService.format("where a = 'it''s a  test'"));
        }

        @Test
        void mehrzeiliges_literal_bleibt_zeilenweise_erhalten() {
            assertEquals("""
                    INSERT INTO t VALUES ('a  b
                    c  d')""", SqlFormatService.format("insert into t values ('a  b\nc  d')"));
        }

        @Test
        void einrueckung_innerhalb_eines_mehrzeiligen_literals_bleibt_erhalten() {
            assertEquals("""
                    INSERT INTO t VALUES ('a
                      b
                        c')""", SqlFormatService.format("insert into t values ('a\n  b\n    c')"));
        }

        @Test
        void abgeschlossene_literal_zeile_wird_normal_eingerueckt() {
            assertEquals("""
                    SELECT (
                        'a  b'
                    ) FROM t""", SqlFormatService.format("select (\n'a  b'\n) from t"));
        }
    }

    @Nested
    @DisplayName("Quotierte Bezeichner werden nicht umgeschrieben")
    class Bezeichner {

        @Test
        void doppelte_anfuehrungszeichen() {
            assertEquals("SELECT \"my  column\" FROM t", SqlFormatService.format("select \"my  column\" from t"));
        }

        @Test
        void backticks() {
            assertEquals("SELECT `weird  name`, [other  col] FROM t",
                    SqlFormatService.format("select `weird  name`, [other  col] from t"));
        }

        @Test
        @DisplayName("Keyword-Form bleibt beim Bezeichner, nur der Code davor wird beendet")
        void keyword_als_bezeichner_bleibt_kleingeschrieben() {
            // Vorher: SELECT ASC, DESC, "SET", [VALUES] - der Bezeichner wurde mitveraendert.
            assertEquals("SELECT ASC, DESC, \"set\", [values] FROM t",
                    SqlFormatService.format("select asc, desc, \"set\", [values] from t"));
        }
    }

    @Nested
    @DisplayName("Mehrwort-JOINs werden konsistent geschrieben")
    class Joins {

        @Test
        void alle_join_varianten() {
            // Vorher: "left outer join" -> "left outer JOIN" und "cross join" -> "cross JOIN",
            // weil "join" im Keyword-Array vor den mehrwortigen Varianten stand.
            //
            // Erwartung hier nachtraeglich geaendert: das kleine "on" war kein
            // Fehler im Test, sondern eine Luecke im Keyword-Set - es war
            // schlicht nicht enthalten. "ON" ist jetzt aufgenommen.
            assertEquals("""
                    SELECT *
                    LEFT OUTER JOIN a ON a.id = b.id
                    LEFT JOIN c ON c.id = b.id
                    RIGHT OUTER JOIN d ON d.id = b.id
                    FULL JOIN e ON e.id = b.id
                    CROSS JOIN f
                    NATURAL JOIN g
                    INNER JOIN h ON h.id = b.id""", SqlFormatService.format("""
                    select *
                    left outer join a on a.id = b.id
                    left join c on c.id = b.id
                    right outer join d on d.id = b.id
                    full join e on e.id = b.id
                    cross join f
                    natural join g
                    inner join h on h.id = b.id"""));
        }
    }

    @Nested
    @DisplayName("Kommentare")
    class Kommentare {

        @Test
        void zeilenkommentar_bekommt_keywords_gross() {
            assertEquals("-- WHERE x = 1", SqlFormatService.format("-- where x = 1"));
        }

        @Test
        void fortsetzende_kommentarzeile_wird_eingerueckt() {
            assertEquals("--     AND x = 1", SqlFormatService.format("-- and x = 1"));
        }

        @Test
        void leerer_kommentar() {
            assertEquals("--", SqlFormatService.format("--"));
        }

        @Test
        void blockkommentar_bleibt_buchstabentreu() {
            assertEquals("""
                    /*
                       *  diagramm
                         * ----
                       */
                    SELECT 1""", SqlFormatService.format("/*\n   *  diagramm\n     * ----\n   */\nselect 1"));
        }

        @Test
        void inline_blockkommentar_bleibt_erhalten() {
            assertEquals("SELECT /*  keep   me  */ 1", SqlFormatService.format("select /*  keep   me  */ 1"));
        }
    }

    @Nested
    @DisplayName("Einrueckung nach Klammerebene")
    class Einrueckung {

        @Test
        void verschachtelte_queries_werden_tiefengestuft() {
            // Vorher war die Ausgabe flach: keine Klammerverfolgung.
            assertEquals("""
                    SELECT * FROM (
                        SELECT * FROM (
                            SELECT * FROM x WHERE a = 1 AND b = 2
                        ) t1
                    ) t2""", SqlFormatService.format("""
                    select * from (
                    select * from (
                    select * from x where a = 1 and b = 2
                    ) t1
                    ) t2"""));
        }

        @Test
        void semikolon_setzt_die_ebene_zurueck() {
            assertEquals("""
                    SELECT (
                        FROM t;
                    SELECT (
                        FROM t""", SqlFormatService.format("select (\nfrom t;\nselect (\nfrom t"));
        }

        @Test
        void klammern_in_literalen_zahlen_nicht() {
            assertEquals("SELECT '('", SqlFormatService.format("select '('"));
        }
    }

    @Nested
    @DisplayName("Leerraum und Randfaelle")
    class Randfaelle {

        @Test
        void mehrere_leerzeilen_werden_auf_eine_reduziert() {
            assertEquals("a\n\nb", SqlFormatService.format("a\n\n\n\nb"));
        }

        @Test
        void fuehrende_leerzeilen_entfallen() {
            assertEquals("SELECT 1", SqlFormatService.format("\n\n  select 1"));
        }

        @Test
        void windows_zeilenenden() {
            assertEquals("SELECT 1", SqlFormatService.format("select 1\r\n"));
        }

        @Test
        void leerer_eingabetext() {
            assertEquals("", SqlFormatService.format("   "));
        }

        @Test
        void null_eingabetext() {
            assertEquals("", SqlFormatService.format(null));
        }
    }

    @Nested
    @DisplayName("Idempotenz")
    class Idempotenz {

        @Test
        void zweimal_formatieren_aendert_nichts() {
            String once = SqlFormatService.format("select  a ,b from t where a=1 and b=2\norder by a desc");
            assertEquals(once, SqlFormatService.format(once));
        }

        @Test
        void zweimal_formatieren_von_kommentaren_aendert_nichts() {
            String once = SqlFormatService.format("-- and  x = 1");
            assertEquals(once, SqlFormatService.format(once));
        }
    }

    @Nested
    @DisplayName("Locale")
    class Locales {

        @Test
        @DisplayName("deutsche Locale verfaelscht das i nicht")
        void deutsche_locale() {
            withDefault(Locale.GERMANY, () ->
                    assertEquals("INSERT INTO t", SqlFormatService.format("insert into t")));
        }

        @Test
        @DisplayName("tuerkische Locale erzeugt kein I mit Punkt")
            // Ohne Locale.ROOT ergaebe "insert into".toUpperCase() -> "İNSERT İNTO".
        void tuerkische_locale_bleibt_korrekt() {
            withDefault(new Locale("tr", "TR"), () -> {
                String formatted = SqlFormatService.format("insert into t");
                assertEquals("INSERT INTO t", formatted);
                assertFalse(formatted.contains("İ"), "I mit Punkt gefunden: " + formatted);
            });
        }

        @Test
        @DisplayName("Umlaute in Literalen bleiben unangetastet")
        void umlaute_im_literal() {
            withDefault(Locale.GERMANY, () ->
                    assertEquals("SELECT 'straße' FROM t", SqlFormatService.format("select 'straße' from t")));
        }

        private void withDefault(Locale locale, Runnable action) {
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(locale);
                action.run();
            } finally {
                Locale.setDefault(previous);
            }
        }
    }

    @Nested
    @DisplayName("Dollar-Quoting und typisierte Literale bleiben unangetastet")
    class QuotingVarianten {

        @Test
        void dollar_quote_bleibt_kleingeschrieben() {
            // Der Funktionskoerper darf nicht formatiert werden - "select" bleibt
            // klein, obwohl es sonst ein Keyword waere.
            assertTrue(SqlFormatService.format("create function f() as $$ select 1 from dual $$")
                            .endsWith("$$ select 1 from dual $$"),
                    "Dollar-Body wurde formatiert");
        }

        @Test
        void getaggtes_dollar_tag_bleibt_kleingeschrieben() {
            assertEquals("SELECT $body$ select 1 $body$",
                    SqlFormatService.format("select $body$ select 1 $body$"));
        }

        @Test
        @DisplayName("unterschiedliche Tags schliessen nicht - sonst wuerde der Rest zu Code")
        void dollar_tags_muessen_uebereinstimmen() {
            // $a$ ... $b$ ist nicht geschlossen, also bleibt alles nach $a$ geschuetzt.
            assertEquals("SELECT $a$ select 1 $b$",
                    SqlFormatService.format("select $a$ select 1 $b$"));
        }

        @Test
        @DisplayName("Parameter $1 ist kein Dollar-Quoting")
        void parameter_ist_kein_quoting() {
            assertEquals("SELECT $1 FROM t", SqlFormatService.format("select $1 from t"));
        }

        @Test
        @DisplayName("Semikolon im Dollar-Body setzt die Einrueckung nicht zurueck")
        void semikolon_im_dollar_body() {
            assertEquals("""
                    SELECT (
                        $$ a; b $$
                        FROM t""", SqlFormatService.format("select (\n$$ a; b $$\nfrom t"));
        }

        @Test
        @DisplayName("E'...' bleibt zusammen - der Backslash darf das Literal nicht schliessen")
        void escape_string_bleibt_woertlich() {
            assertEquals("SELECT E'it\\'s a  test' FROM t",
                    SqlFormatService.format("select E'it\\'s a  test' from t"));
        }

        @Test
        @DisplayName("Bezeichner, die auf e enden, sind kein Escape-Literal")
        void bezeichner_auf_e_ist_kein_escape_literal() {
            assertEquals("SELECT some_e'x' FROM t", SqlFormatService.format("select some_e'x' from t"));
        }

        @Test
        void escape_string_ueber_zeilengrenze() {
            assertEquals("SELECT E'a\nb\\'c'", SqlFormatService.format("select E'a\nb\\'c'"));
        }
    }

    @Nested
    @DisplayName("Apostroph im Kommentar")
    class KommentarApostroph {

        @Test
        @DisplayName("Prosa-Kommentar mit Apostroph verliert nichts")
        void apostroph_zerstoert_keinen_kommentar() {
            // "it" ist kein Keyword und bleibt klein - entscheidend ist, dass
            // "where" trotz des Apostrophs gross geschrieben wird. Vorher oeffnete
            // der Apostroph in "it's" ein Literal und schuetzte den Rest.
            assertEquals("-- it's 5 o'clock WHERE x = 1",
                    SqlFormatService.format("-- it's 5 o'clock where x = 1"));
        }

        @Test
        @DisplayName("Kommentar mit Literal und weiterem Keyword")
        void kommentar_mit_literal() {
            // Bewusst normalisiert: der Rumpf eines -- Kommentars ist Prosa, also
            // wird sein Leerraum kollabiert. Blockkommentare, in denen ASCII-Arten
            // stehen, bleiben dagegen byteweise erhalten.
            assertEquals("-- WHERE a = 'x y' AND b = 2",
                    SqlFormatService.format("-- where a = 'x  y' and b = 2"));
        }
    }

    @Nested
    @DisplayName("Zustand ueber Zeilengrenzen")
    class Zustand {

        @Test
        @DisplayName("Literal endet und der Kommentar danach oeffnet es nicht neu")
        void literal_ende_oeffnet_kein_neues() {
            // Das Literal endet an b', der Kommentar dahinter wird nicht als
            // neues Literal gelesen. Die Zeile bleibt unveraendert, danach
            // geht es mit normaler Einrueckung weiter.
            assertEquals("""
                    SELECT (
                        'a
                    b' -- and x = 1
                        FROM t""", SqlFormatService.format("select (\n'a\nb' -- and x = 1\nfrom t"));
        }

        @Test
        @DisplayName("Blockkommentar schliesst und oeffnet direkt ein Literal")
        void kommentar_ende_oeffnet_literal() {
            // " */ select 'a" ist eine Kommentar-Fortsetzungszeile und wird
            // deshalb bewusst unveraendert uebernommen: saehe man dort nach dem
            // schliessenden "*/" im Code nach, koennte das Literal beschaedigt
            // werden. Konservativ, aber verlustfrei.
            assertEquals("/*\n */ select 'a\nb'",
                    SqlFormatService.format("/*\n */ select 'a\nb'"));
        }

        @Test
        @DisplayName("unterminierter Blockkommentar mitten in der Zeile")
        void blockkommentar_mitten_in_zeile() {
            // Die Folgezeile gehoert zum Kommentar und bleibt daher unveraendert.
            assertEquals("SELECT 1 /* offen\nfrom t",
                    SqlFormatService.format("select 1 /* offen\nfrom t"));
        }
    }

    @Nested
    @DisplayName("Konsistenz zum Detektor")
    class DetektorKonsistenz {

        @Test
        @DisplayName("typische Anweisungen werden vollstaendig grossgeschrieben")
        void anweisungen_gross() {
            // Nicht jedes Startwort des Detektors ist ein eigenes Schluesselwort:
            // "insert" und "delete" kommen nur als "insert into" bzw. "delete from"
            // vor, weil INSERT(...) auch eine SQL-Funktion ist und delete gut als
            // Spaltenname taugt. Geprueft wird deshalb das reale Verhalten.
            assertTrue(SqlFormatService.format("insert into t values (1)").startsWith("INSERT INTO"),
                    "INSERT INTO");
            assertTrue(SqlFormatService.format("delete from t").startsWith("DELETE FROM"),
                    "DELETE FROM");
            assertTrue(SqlFormatService.format("update t set a = 1 where id = 2").startsWith("UPDATE t SET"),
                    "UPDATE ... SET");
            String merge = SqlFormatService.format("merge into t using s on t.id = s.id");
            assertTrue(merge.contains("MERGE INTO") && merge.contains("USING") && merge.contains(" ON "),
                    "MERGE: " + merge);
            assertTrue(SqlFormatService.format("create table t (id int)").startsWith("CREATE TABLE"),
                    "CREATE TABLE");
            assertTrue(SqlFormatService.format("truncate table t").startsWith("TRUNCATE TABLE"),
                    "TRUNCATE TABLE");
            assertTrue(SqlFormatService.format("alter table t add c int").startsWith("ALTER TABLE"),
                    "ALTER TABLE");
            assertTrue(SqlFormatService.format("create index i on t (a)").startsWith("CREATE INDEX"),
                    "CREATE INDEX");
            // "if" fehlt absichtlich, "exists" nicht.
            assertTrue(SqlFormatService.format("drop table if exists t").startsWith("DROP TABLE if EXISTS"),
                    "DROP: " + SqlFormatService.format("drop table if exists t"));
        }

        @Test
        @DisplayName("die Ausnahme bleibt auch wirklich aus")
        void comment_bleibt_klein() {
            // Sonst koennte jemand das Weglassen wieder "versehentlich" rueckgaengig
            // machen, ohne die Begruendung zu lesen.
            assertEquals("comment", SqlFormatService.format("comment"));
        }
    }

    @Test
    void formatierung_eines_grossen_textes_bleibt_schnell() {
        String big = "select  a ,  b from  users  where id = 1 and name = 'x  y' order by a desc".repeat(2000);

        for (int i = 0; i < 5; i++) {
            SqlFormatService.format(big);
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            SqlFormatService.format(big);
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
        }
        // Vor dem Umbau: 22 Pattern.compile-Aufrufe pro Zeile.
        assertTrue(best < 70, "2000 Zeilen brauchten " + best + " ms, erwartet < 70 ms");
    }
}
