package signaliduna;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressionstests fuer {@link SqlDetector}.
 *
 * <p>Die alte Heuristik gab frei, sobald irgendwo eine "--"-Zeile stand, und lehnte
 * gleichzeitig echtes SQL ohne Support-Keyword ab. Beide Richtungen sind hier festgenagelt.
 */
class SqlDetectorTest {

    @Nested
    @DisplayName("Kein SQL wird abgelehnt")
    class Abgelehnt {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                // Fliesstext, derzufaeellig ein Schluesselwort enthaelt
                "Please select an option * from the list",
                "Meeting notes\n-- action items: order by phone",
                "Lieferung kam heute an, alles ok",
                // Kommentare allein genuegen nie
                "key: value\n-- a yaml comment",
                "-- nur ein Kommentar",
                "/* nur ein Blockkommentar */",
                // beginnt nicht mit einem Statement
                "id = 1",
                "'select from t'",
        })
        void kein_sql(String text) {
            assertFalse(SqlDetector.looksLikeSql(text), text);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n\n\t", "  \r\n  "})
        void leerer_text(String text) {
            assertFalse(SqlDetector.looksLikeSql(text));
        }
    }

    @Nested
    @DisplayName("SQL wird erkannt")
    class Erkannt {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                // Vorher abgelehnt, weil "TABLE" kein Support-Keyword war
                "TRUNCATE TABLE t",
                "CREATE TABLE t(i INT)",
                "DELETE FROM t",
                "select * from a where b=1",
                "SELECT id, name FROM users WHERE active = 1 ORDER BY name",
                "UPDATE t SET a = 1",
                "INSERT INTO t VALUES (1)",
                "with x as (select 1) select * from x",
                "ALTER TABLE t ADD COLUMN c INT",
                "DROP TABLE t",
                // Fragmente duerfen nutzbar bleiben
                "WHERE a = 1 AND b = 2",
                "and c = 3",
                "GROUP BY a",
                "UNION SELECT 1",
                // fuehrender Kommentar stoert nicht
                "-- Kommentar\nwith x as (select 1) select * from x",
                "/* lead */ insert into t values (1)",
                "\n\n   select 1",
                "SELECT * FROM t -- ok",
        })
        void sql(String text) {
            assertTrue(SqlDetector.looksLikeSql(text), text);
        }

        @Test
        @DisplayName("grosser/kleiner geschriebenes Statement wird erkannt")
        void gemischte_gross_klein_schreibung() {
            assertTrue(SqlDetector.looksLikeSql("SeLeCt * FrOm t"));
        }

        @Test
        @DisplayName("deutsche Umlaute stoeren die Erkennung nicht")
        void umlaute() {
            java.util.Locale previous = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.GERMANY);
                assertTrue(SqlDetector.looksLikeSql("select 'straße' from straßennamen"));
            } finally {
                java.util.Locale.setDefault(previous);
            }
        }
    }
}
