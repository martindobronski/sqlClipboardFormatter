package signaliduna;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Erkennt, ob ein Text mit SQL beginnt. Die alte Logik ließ jeden Text zu,
 * der irgendwo eine "--"-Zeile enthielt, und lehnte gleichzeitig echtes SQL
 * wie "TRUNCATE TABLE t" ab. Jetzt entscheidet das erste sinntragende Wort.
 */
final class SqlDetector {

    /** Führender Leerraum sowie Kommentare vor dem ersten Statement. */
    private static final Pattern LEADING_NOISE = Pattern.compile(
            "\\A(?:[ \\t\\r\\n]+|--[^\\n]*|/\\*.*?\\*/)*",
            Pattern.DOTALL);

    private static final Pattern LEADING_WORD = Pattern.compile("\\A([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * Wörter, mit denen ein SQL-Text beginnen kann. Enthält bewusst auch
     * Klausel-Anfänge, damit Fragmente wie "WHERE a = 1" nutzbar bleiben -
     * Fließtext wie "Please select an option" fällt dadurch weiter auf.
     *
     * <p>Paketsichtbar, damit ein Test die Konsistenz zum Formatter prüfen kann.
     */
    static final Set<String> STATEMENT_STARTERS = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "COMMENT",
            "EXPLAIN", "ANALYZE", "VACUUM", "CALL", "SET", "SHOW", "USE", "VALUES",
            "DECLARE", "BEGIN", "COMMIT", "ROLLBACK", "EXEC", "EXECUTE",
            "WHERE", "AND", "OR", "FROM", "JOIN", "LEFT", "RIGHT", "INNER", "FULL",
            "CROSS", "ON", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "INTO");

    private SqlDetector() {
    }

    /**
     * @return {@code true}, wenn der Text mit einem SQL-Schlüsselwort beginnt
     */
    static boolean looksLikeSql(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String stripped = LEADING_NOISE.matcher(text).replaceFirst("");
        if (stripped.isBlank()) {
            // Nur Kommentare, kein Statement.
            return false;
        }

        var matcher = LEADING_WORD.matcher(stripped);
        return matcher.find()
                && STATEMENT_STARTERS.contains(matcher.group(1).toUpperCase(Locale.ROOT));
    }
}
