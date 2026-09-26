package signaliduna;

import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.github.vertical_blank.sqlformatter.core.FormatConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formatiert SQL nach den Regeln von sql-formatter - der ueblichen Referenz
 * fuer "SQL schoen machen". Gegenueber {@link SqlFormatService} ist das ein
 * echter Umbruch statt einer Normalisierung: Klauseln kommen in eigene
 * Zeilen, SELECT-Listen untereinander, Unterabfragen werden eingerueckt und
 * Satzzeichen bekommen saubere Abstaende.
 *
 * <p><b>Warum trotzdem ein eigener Fallback.</b> sql-formatter ist ein
 * <em>Whitespace</em>-Formatierer, kein Validator: er wirft bei unbrauchbarem
 * Input keine Ausnahme, sondern setzt ihn um. Zwei Konstrukte zerlegt er
 * nachweislich falsch und werden deshalb an {@link SqlFormatService}
 * abgegeben, der niemals Inhalt umschreibt:
 *
 * <ul>
 *   <li>{@code $$ … $$} bzw. {@code $tag$ … $tag$} - nur mit
 *       {@link SqlDialect#POSTGRESQL} korrekt, sonst wird {@code $$} zu
 *       {@code $ $} und der Funktionskoerper wird als Code formatiert.
 *   <li>Typisierte Literale - {@code E'…'} und {@code DATE'…'} werden vom
 *       Leerzeichen getrennt ({@code DATE '…'}), wodurch gueltiges SQL
 *       ungueltig wird. In allen Dialekten.
 * </ul>
 *
 * <p>Referenz fuer die Zielregeln: <a href="https://github.com/sql-formatter-org/sql-formatter">
 * sql-formatter</a>, Regelkatalog analog <a href="https://sqlfluff.com/">SQLFluff</a>.
 */
final class SqlPrettyFormatter {

    /**
     * 4 Leerzeichen Einrueckung und Keywords gross - entspricht dem Stil, den
     * {@link SqlFormatService} zuvor verwendet hat, damit der Wechsel fuer
     * den Anwender nicht wie ein Stilbruch wirkt.
     */
    private static final FormatConfig CONFIG = FormatConfig.builder()
            .indent(" ".repeat(SqlFormatService.INDENT_WIDTH))
            .uppercase(true)
            .maxColumnLength(100)
            .build();

    /** {@code $$} oder {@code $tag$} - Tag darf nicht mit Ziffer beginnen, sonst matchen {@code $1}. */
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$");

    /**
     * Typisierte Literale, die der {@code E}-Marker einleitet: {@code E'…'}
     * sowie {@code DATE'…'}, {@code TIMESTAMP'…'} und Verwandte. sql-formatter
     * trennt das Kennwort vom Literal - aus {@code DATE'2020-01-01'} wird
     * {@code DATE '2020-01-01'}, was kein gültiges SQL mehr ist.
     *
     * <p>Der Lookbehind verhindert Fehltreffer auf Bezeichner, die auf einen
     * dieser Namen enden: {@code some_col_e'x'} und {@code update'…'} gehören
     * nicht dazu.
     */
    private static final Pattern TYPED_LITERAL = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:E|DATE|DATETIME|TIME|TIMESTAMP|TIMESTAMPTZ|INTERVAL)'",
            Pattern.CASE_INSENSITIVE);

    /**
     * Zeichen, aus denen SQL-Operatoren bestehen. Anfuehrungszeichen, Backtick,
     * {@code $}, {@code .} und Klammern fehlen bewusst: sie oeffnen Literale,
     * Bezeichner oder Dollar-Quoting, und koennten daher Fehltreffer liefern,
     * ohne je Teil eines Operators zu sein.
     */
    private static final String OPERATOR_CHARS = "+*/%<>=!~^&|:?@#-";

    /** Einzeln stehende Operatorzeichen, Leerraum wird beim Vergleich ignoriert. */
    private static final Pattern OPERATOR_CHAR = Pattern.compile("[" + OPERATOR_CHARS + "]");

    /**
     * Mehrteilige Operatoren. Nur fuer diese gilt, dass ihre Zeichen zusammenhaengend
     * bleiben muessen - zwei nebeneinanderstehende Einzeloperatoren wie das
     * {@code =} und {@code -} in {@code a=-1} duerfen sich dagegen frei trennen.
     */
    private static final String[] COMPOUND_OPERATORS = {
            "||", "::", "->>", "->", "#>>", "#>", "<>", "!=", "<=", ">=",
            "<<", ">>", "**", "!~~", "!~", "~~", "~*", "&&", "..",
    };

    /** Zu jedem mehrteiligen Operator das Muster seiner "zerschnittenen" Form. */
    private static final List<Pattern> SPLIT_OPERATORS = Arrays.stream(COMPOUND_OPERATORS)
            .map(SqlPrettyFormatter::splitPattern)
            .toList();

    /**
     * Findet einen Operator, der es weder vorher noch nachher gibt.
     *
     * <p>sql-formatter zerlegt mehrteilige Operatoren je nach Dialekt, ohne
     * Ruecksicht auf ihre Gueltigkeit: aus {@code a || 'x'} wird in PostgreSQL
     * und T-SQL {@code a | | 'x'}, aus {@code #>> '{k}'} wird im Standard-Dialekt
     * {@code # > > '{k}'}, aus {@code a::text} wird in PL/SQL {@code A: :text}.
     * Solche Ergebnisse sind kein SQL mehr.
     *
     * <p>Statt eine Liste bekannter Operatoren zu pflegen, werden die tatsaechlich
     * vorhandenen verglichen - so faellt auch ein Bruch auf, den die Library
     * nach einem Update einfuehrt.
     *
     * @return der auffaellige Operator oder {@code null}, wenn keiner auffaellt
     */
    static String alteredOperator(String raw, String formatted) {
        // Teil 1: Es darf kein Operatorzeichen hinzukommen, wegfallen oder die
        // Reihenfolge wechseln. Leerraum ist hier egal, weil die Library ihn
        // rund um Operatoren selbst setzt und entfernt.
        if (!operatorCharacters(raw).equals(operatorCharacters(formatted))) {
            return "<geaendert>";
        }

        // Teil 2: Ein mehrteiliger Operator, der im Eingang existierte, darf in
        // der Ausgabe nicht zwischen seinen Zeichen aufgeschnitten sein.
        for (int i = 0; i < COMPOUND_OPERATORS.length; i++) {
            if (raw.contains(COMPOUND_OPERATORS[i]) && SPLIT_OPERATORS.get(i).matcher(formatted).find()) {
                return COMPOUND_OPERATORS[i];
            }
        }
        return null;
    }

    /** {@code ||} als {@code \|\s+\|} - dieselbe Folge, aber mit Leerraum dazwischen. */
    private static Pattern splitPattern(String operator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < operator.length(); i++) {
            if (i > 0) {
                sb.append("\\s+");
            }
            sb.append(Pattern.quote(String.valueOf(operator.charAt(i))));
        }
        return Pattern.compile(sb.toString());
    }

    private static String operatorCharacters(String text) {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = OPERATOR_CHAR.matcher(text);
        while (matcher.find()) {
            sb.append(matcher.group());
        }
        return sb.toString();
    }

    /**
     * Prueft, ob der Text einen Backtick-Bezeichner enthaelt, der weder in einem
     * Literal noch in einem Kommentar steht.
     *
     * <p>sql-formatter setzt Leerzeichen in Backtick-Bezeichner: aus
     * {@code `col`} wird im Standard-Dialekt {@code ` col `}. In MySQL ist das
     * ein <em>anderer</em> Bezeichner, also keine Kosmetik. Deshalb wird bei
     * Backticks auf MySQL umgeschaltet - genau wie bei Dollar-Quoting.
     *
     * <p>Ein Backtick in {@code -- don't use `x`} oder in {@code 'ein `zeichen'}
     * zaehlt nicht, deshalb laeuft der Text zeichenweise durch statt ueber eine
     * Regex: eine Regex kann einen Kommentar nicht vom Code unterscheiden.
     * Dollar-Quoting wird bewusst nicht behandelt - dort greift ohnehin schon
     * die PostgreSQL-Umschaltung, und {@code $$} existiert in MySQL nicht.
     */
    static boolean hasBacktickIdentifier(String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                i = endeVonZeilenkommentar(text, i);
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i = endeVonBlockkommentar(text, i);
            } else if (c == '\'' || c == '"') {
                i = endeVonQuoted(text, i, c);
            } else if (c == '`') {
                return true;
            } else {
                i++;
            }
        }
        return false;
    }

    private static int endeVonZeilenkommentar(String text, int start) {
        int zeilenumbruch = text.indexOf('\n', start);
        return zeilenumbruch < 0 ? text.length() : zeilenumbruch;
    }

    private static int endeVonBlockkommentar(String text, int start) {
        int ende = text.indexOf("*/", start + 2);
        return ende < 0 ? text.length() : ende + 2;
    }

    /** {@code 'a''b'} ist ein Literal, deshalb wird ein verdoppeltes Zeichen uebersprungen. */
    private static int endeVonQuoted(String text, int start, char quote) {
        for (int i = start + 1; i < text.length(); i++) {
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i++;
                } else {
                    return i + 1;
                }
            }
        }
        return text.length();
    }

    /**
     * Beantwortet die Frage, die beim Umschalten gestellt wird: Welche Dialekte
     * wuerden diesen Text gegenueber Standard SQL ueberhaupt veraendern?
     *
     * <p>Das ist eine Eigenschaft des Textes, nicht des Dialekts - dieselbe
     * Liste kann sich von Query zu Query aendern. Deshalb wird hier
     * gerechnet statt eine statische Auswahlliste gepflegt zu haben, und
     * deshalb auch kein Dialog angeboten, der nichts bewirkt.
     *
     * <p>Enthalten sind nur Dialekte, die etwas anderes <em>liefern</em> - auch
     * dann, wenn das der Rueckfall ist. Massgeblich ist damit genau das, was
     * der Nutzer nach einem Klick in der Zwischenablage vorfindet.
     *
     * @return die wirksamen Dialekte in {@link SqlDialect}-Reihenfolge, nie {@code null}
     */
    static List<SqlDialect> effectiveDialects(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String referenz = format(text, SqlDialect.STANDARD).sql();
        List<SqlDialect> wirksam = new ArrayList<>();
        for (SqlDialect dialect : SqlDialect.values()) {
            if (dialect != SqlDialect.STANDARD && !format(text, dialect).sql().equals(referenz)) {
                wirksam.add(dialect);
            }
        }
        return wirksam;
    }

    /**
     * Ergebnis einer Formatierung.
     *
     * @param sql           der formatierte Text
     * @param bestPractice  {@code true} wenn sql-formatter benutzt wurde,
     *                      {@code false} wenn der konservative Fallback lief
     * @param notice        kurzer Hinweis fuer die Statuszeile, {@code null} im Regelfall
     */
    record Result(String sql, boolean bestPractice, String notice) {
    }

    private SqlPrettyFormatter() {
    }

    static Result format(String raw, SqlDialect requested) {
        if (raw == null || raw.isBlank()) {
            return new Result("", true, null);
        }

        SqlDialect dialect = requested == null ? SqlDialect.STANDARD : requested;
        String notice = null;

        if (dialect != SqlDialect.POSTGRESQL && DOLLAR_QUOTE.matcher(raw).find()) {
            dialect = SqlDialect.POSTGRESQL;
            notice = "Dollar-Quoting erkannt - Dialekt auf PostgreSQL gesetzt.";
        }

        // Backticks gibt es nur in MySQL. Im Standard-Dialekt wuerde der
        // Formatierer Leerzeichen in den Bezeichner setzen und damit seinen
        // Namen veraendern, also wird hier ebenfalls umgeschaltet.
        if (notice == null && dialect == SqlDialect.STANDARD && hasBacktickIdentifier(raw)) {
            dialect = SqlDialect.MYSQL;
            notice = "Backtick-Bezeichner erkannt - Dialekt auf MySQL gesetzt.";
        }

        if (TYPED_LITERAL.matcher(raw).find()) {
            return conservative(raw, "Typisierte Literale wie E'…' oder DATE'…' trennt die Library - nur normalisiert.");
        }

        String formatted;
        try {
            formatted = SqlFormatter.of(dialect.libraryDialect()).format(raw, CONFIG);
        } catch (RuntimeException | StackOverflowError ex) {
            return conservative(raw, "Library-Formatierung fehlgeschlagen - nur normalisiert.");
        }

        String kaputt = alteredOperator(raw, formatted);
        if (kaputt != null) {
            return conservative(raw, "Operator '%s' waere von der Library zerschnitten - nur normalisiert.".formatted(kaputt));
        }

        // Der gewaehlte Dialekt ist in der Library nur oberflaechlich umgesetzt.
        // Fuer gewoehnliche Abfragen liefern mehrere Dialekte exakt dasselbe -
        // das ist ehrlicher zu sagen, als eine Wirkung vorzutaeuschen. Die
        // Vergleichsformatierung entfaellt fuer Standard, das ist der
        // haeufigste Fall und wuerde sonst jeden Aufruf verdoppeln.
        if (requested != null && requested != SqlDialect.STANDARD
                && formatted.equals(SqlFormatter.of(SqlDialect.STANDARD.libraryDialect()).format(raw, CONFIG))) {
            notice = "Dialekt '%s' aendert diesen Text nicht.".formatted(requested.label());
        }

        return new Result(mitZeilenendenVon(raw, formatted), true, notice);
    }

    private static Result conservative(String raw, String notice) {
        return new Result(mitZeilenendenVon(raw, SqlFormatService.format(raw)), false, notice);
    }

    /**
     * Gibt dem Ergebnis die Zeilenenden der Eingabe zurueck.
     *
     * <p>Die Library und der Fallback arbeiten mit {@code \n}. Auf Windows ist
     * CRLF aber der Normalfall: ein aus der Zwischenablage gelesenes
     * {@code .sql}-Skript hat es, und ohne diesen Schritt kaeme es als
     * gemischte oder reine LF-Datei zurueck. Das waere eine Aenderung an der
     * Datei, die niemand angefordert hat, und wuerde beim erneuten Einlesen
     * sichtbar.
     *
     * <p>Gezählt wird nur {@code \r\n}, nicht jedes {@code \r}: ein einzelnes
     * {@code \r} in einem Literal ist Text, kein Zeilenende, und wuerde bei
     * einem unsichtbaren Umbruch zu {@code \r\r\n} aufgeblaeht.
     *
     * @return der Text mit den Zeilenenden der Eingabe
     */
    private static String mitZeilenendenVon(String raw, String text) {
        if (text.indexOf('\n') < 0) {
            return text;
        }
        int crlf = 0;
        int zeilen = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\n') {
                zeilen++;
                if (i > 0 && raw.charAt(i - 1) == '\r') {
                    crlf++;
                }
            }
        }
        if (zeilen == 0 || crlf * 2 < zeilen) {
            return text;
        }
        return text.replace("\n", "\r\n");
    }
}
