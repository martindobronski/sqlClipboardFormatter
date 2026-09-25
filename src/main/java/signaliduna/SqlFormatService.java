package signaliduna;

import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formatierung von SQL-Text. Bewusst ohne Swing-Abhängigkeit, damit die Logik
 * isoliert testbar bleibt.
 *
 * <p>Kernregel: String-Literale, quotierte Bezeichner und Kommentare werden
 * inhaltlich nie umgeschrieben. Ein Text wie {@code where name = 'please select one'}
 * behält sein Literal unverändert - nur der Code davor wird bereinigt.
 */
final class SqlFormatService {

    /** Anzahl Leerzeichen je Einrückungsebene. */
    static final int INDENT_WIDTH = 4;

    private static final String INDENT = " ".repeat(INDENT_WIDTH);

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * Schluesselwoerter des Fallback-Formatierers. Mehrwort-Alternative stehen
     * vorn, damit {@code union\s+all} vor {@code union} greift; kurze Woerter
     * ueberlappen sich thanks {@code \b} nicht ({@code in} trifft nicht
     * {@code into}, {@code or} trifft nicht {@code order}).
     *
     * <p>Bewusst <em>nicht</em> enthalten sind Woerter, die haeufig unquotiert als
     * Spaltenname verwendet werden: {@code comment}, {@code key}, {@code row},
     * {@code first}, {@code last}, {@code open}, {@code close}, {@code level},
     * {@code if}. Ein Fehltreffer verdirbt den Bezeichner, ein fehlendes
     * Schluesselwort verdirbt nur die Grossschreibung - das zweite ist der
     * guenstigere Fehler. Wer {@code comment} als Spalte braucht, schreibt
     * {@code "comment"}; quotierte Bezeichner sind ohnehin geschuetzt.
     */
    private static final Pattern KEYWORDS = Pattern.compile(
            "\\b(?:"
                    // zuerst die mehrwortigen Phrasen
                    + "insert\\s+into|delete\\s+from|union\\s+all|group\\s+by|order\\s+by"
                    + "|partition\\s+by|primary\\s+key|foreign\\s+key"
                    + "|left\\s+(?:outer\\s+)?join|right\\s+(?:outer\\s+)?join"
                    + "|full\\s+(?:outer\\s+)?join"
                    + "|natural\\s+(?:left\\s+|right\\s+|full\\s+)?join"
                    + "|inner\\s+join|cross\\s+join"
                    // dann die Einwort-Schluesselwoerter
                    + "|select|from|where|and|or|not|in|is|null|having"
                    + "|join|on|using|as|into|update|set|values|like|ilike"
                    + "|desc|asc|distinct|all|except|intersect|limit|offset|union"
                    + "|case|when|then|else|end|exists|between|recursive|lateral"
                    + "|create|alter|drop|truncate|table|view|index|with|returning"
                    + "|cast|collate|over|partition|window|rows|range|nulls"
                    + "|merge|replace|explain|analyze|declare|begin|commit|rollback"
                    + "|grant|revoke|vacuum|call|show|use|exec|execute|only"
                    + "|default|references|constraint|primary|foreign|fetch"
                    + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Ein geschützter Bereich im Quelltext: Literale, quotierte Bezeichner
     * und Kommentare. {@link Span#closed()} ist {@code false}, wenn der Bereich
     * unterminiert ist und sich auf die Folgezeilen fortsetzt.
     *
     * @param kind       welche Art von Bereich es ist
     * @param quote      der einleitende Delimiter, {@code '\0'} bei Kommentaren
     * @param backslash  ob {@code \} im Bereich ein Escapezeichen ist (nur E'..')
     */
    private record Span(int length, boolean closed, Kind kind, char quote, boolean backslash) {

        private static Span of(int length, Kind kind) {
            return new Span(length, true, kind, '\0', false);
        }

        private static Span open(int length, Kind kind, char quote, boolean backslash) {
            return new Span(length, false, kind, quote, backslash);
        }
    }

    private enum Kind {
        STRING,
        QUOTED_IDENT,
        LINE_COMMENT,
        BLOCK_COMMENT;

        boolean isLiteral() {
            return this == STRING || this == QUOTED_IDENT;
        }
    }

    /**
     * Der über Zeilengrenzen offene Bereich.
     *
     * <p>Eigene Klasse statt zweier {@code boolean}-Variablen: Blockkommentar
     * und Literal sind verschiedene Zustände, und ein Bereich kann in
     * Folgezeilen mit einem anderen Delimiter enden als er begonnen hat. Der
     * Delimiter wird deshalb mitgeführt.
     */
    private static final class OpenState {

        private final boolean literal;
        private final boolean blockComment;
        private final char quote;
        private final boolean backslash;

        private OpenState(Span span) {
            this.literal = span != null && span.kind().isLiteral();
            this.blockComment = span != null && span.kind() == Kind.BLOCK_COMMENT;
            this.quote = span == null ? '\0' : span.quote();
            this.backslash = span != null && span.backslash();
        }

        static OpenState closed() {
            return new OpenState(null);
        }

        boolean isOpen() {
            return literal || blockComment;
        }
    }

    private SqlFormatService() {
    }

    /**
     * Formatiert den kompletten Text: Leerraum-Runs in Codebereichen kollabieren,
     * Keywords groß schreiben, Zeilen nach Klammerebene einrücken.
     *
     * @param raw Eingabetext, darf {@code null} sein
     * @return formatierter Text, nie {@code null}
     */
    static String format(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String[] lines = raw.split("\r?\n", -1);
        StringBuilder out = new StringBuilder(raw.length() + 64);

        int depth = 0;
        OpenState open = OpenState.closed();
        boolean previousLineBlank = false;

        for (String line : lines) {
            String stripped = line.strip();

            if (stripped.isEmpty()) {
                // Mehrere Leerzeilen auf eine reduzieren.
                if (out.length() > 0 && !previousLineBlank) {
                    out.append('\n');
                }
                previousLineBlank = true;
                continue;
            }
            previousLineBlank = false;

            // Fortsetzungszeilen von Kommentar oder Literal werden nur am
            // Zeilenende beschnitten. Einrücken, Kollabieren oder Grossschreiben
            // würde den Inhalt verändern - bei mehrzeiligen Literalen wäre das
            // ein stiller Datenverlust.
            if (open.isOpen()) {
                out.append(line.stripTrailing()).append('\n');
                open = advance(open, line);
                continue;
            }

            if (stripped.startsWith("/*")) {
                out.append(line.stripTrailing()).append('\n');
                int close = stripped.indexOf("*/", 2);
                open = close < 0
                        ? new OpenState(Span.open(0, Kind.BLOCK_COMMENT, '\0', false))
                        : OpenState.closed();
                continue;
            }

            if (stripped.startsWith("--")) {
                out.append(formatCommentLine(stripped)).append('\n');
                depth = nextDepth(stripped, depth);
                continue;
            }

            // Eine Zeile, die mit ")" beginnt, schließt eine Ebene - sie gehört
            // eine Stufe weiter links als ihr Inhalt.
            int indentDepth = stripped.startsWith(")") ? Math.max(0, depth - 1) : depth;

            String formatted = mapCodeRegions(stripped, SqlFormatService::normalizeCode);
            out.append(INDENT.repeat(indentDepth)).append(indentIfContinuation(formatted)).append('\n');

            open = new OpenState(openSpanAtEnd(stripped, 0));
            depth = nextDepth(stripped, depth);
        }

        return out.toString().strip();
    }

    /**
     * Zustand nach einer Fortsetzungszeile. Entscheidet am <em>eingeleiteten
     * Delimiter</em>, nicht am ersten zufällig gefundenen: bei
     * {@code 'a⏎b' -- c} schliesst das {@code '} das Literal, der Kommentar
     * dahinter darf es nicht wieder oeffnen.
     */
    private static OpenState advance(OpenState open, String line) {
        if (open.blockComment) {
            int close = line.indexOf("*/");
            if (close < 0) {
                return open;
            }
            return new OpenState(openSpanAtEnd(line, close + 2));
        }
        return stillOpenLiteral(line, open) ? open : OpenState.closed();
    }

    /**
     * Bleibt das eingeleitete Literal in dieser Zeile offen? Bei Backslash-
     * Escapes zaehlt ein {@code \} als Teil des Zeichens, sonst wuerde
     * {@code E'it\'s} am {@code \'} geschlossen.
     */
    private static boolean stillOpenLiteral(String line, OpenState open) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (open.backslash && c == '\\' && i + 1 < line.length()) {
                i++;
                continue;
            }
            if (c != open.quote) {
                continue;
            }
            if (i + 1 < line.length() && line.charAt(i + 1) == open.quote) {
                i++;
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Kommentarzeile: Marker erhalten, Innenleerraum kollabieren, Keywords
     * gross schreiben. Zeilen, die ein AND/OR/SET fortsetzen, werden wie bisher
     * etwas eingerückt.
     *
     * <p>Wichtig: hier wird bewusst <em>nicht</em> {@link #mapCodeRegions}
     * benutzt. Der Rumpf eines Kommentars enthält keine SQL-Literale, sondern
     * Prosa - ein Apostroph in "-- it's 5 o'clock" würde als öffnendes Literal
     * gelten und den Rest der Zeile fälschlich als geschützt behandeln.
     */
    private static String formatCommentLine(String line) {
        String body = line.substring(2).strip();
        if (body.isEmpty()) {
            return "--";
        }

        return indentIfContinuation(normalizeCode(body), "--", " ");
    }

    private static String indentIfContinuation(String formatted) {
        return indentIfContinuation(formatted, "", "");
    }

    /**
     * @param body      bereits umformatierter Zeileninhalt
     * @param prefix    Präfix wie {@code "--" + INDENT}
     * @param glue      Trenner zwischen Präfix und Inhalt, z. B. {@code " "}
     */
    private static String indentIfContinuation(String body, String prefix, String glue) {
        String upper = body.toUpperCase(Locale.ROOT);
        boolean continuesClause = upper.startsWith("AND ") || upper.startsWith("OR ") || upper.startsWith("SET ");
        return continuesClause ? prefix + INDENT + glue + body : prefix + glue + body;
    }

    /**
     * Arbeitet nur auf den Codeanteilen von {@code text}. Alles zwischen den
     * geschützten Bereichen wird {@code transform} überlassen, geschützte
     * Bereiche werden 1:1 übernommen.
     */
    private static String mapCodeRegions(String text, UnaryOperator<String> transform) {
        if (text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder(text.length());
        StringBuilder code = new StringBuilder();
        int i = 0;

        while (i < text.length()) {
            Span span = protectedSpan(text, i);
            if (span == null) {
                code.append(text.charAt(i));
                i++;
            } else {
                flush(code, result, transform);
                result.append(text, i, i + span.length());
                i += span.length();
            }
        }
        flush(code, result, transform);
        return result.toString();
    }

    private static void flush(StringBuilder code, StringBuilder result, UnaryOperator<String> transform) {
        if (code.length() > 0) {
            result.append(transform.apply(code.toString()));
            code.setLength(0);
        }
    }

    /**
     * Geschützter Bereich ab {@code start}, oder {@code null} wenn dort Code
     * beginnt. Erkannt werden einfache Literale ('..'), Escape-Literale
     * (E'..'), Dollar-Quoting ($$..$$, $tag$..$tag$), quotierte Bezeichner
     * ("..", `..`, [..]) sowie Zeilen- und Blockkommentare.
     */
    private static Span protectedSpan(String s, int start) {
        char c = s.charAt(start);
        switch (c) {
            case '\'':
                return quotedSpan(s, start, c, Kind.STRING, false);
            case '"':
                return quotedSpan(s, start, c, Kind.QUOTED_IDENT, false);
            case '`':
                return quotedSpan(s, start, c, Kind.QUOTED_IDENT, false);
            case '[': {
                int close = s.indexOf(']', start);
                return close < 0 ? null : Span.of(close - start + 1, Kind.QUOTED_IDENT);
            }
            case '$':
                return dollarQuotedSpan(s, start);
            case 'E':
            case 'e':
                // E'...' ist ein Escape-Literal, in dem \' dieQuote ueberlebt.
                // Ohne das E haette der naechste Apostroph das Literal beendet.
                return escapeStringSpan(s, start);
            case '-':
                return start + 1 < s.length() && s.charAt(start + 1) == '-'
                        ? Span.of(lineCommentLength(s, start), Kind.LINE_COMMENT)
                        : null;
            case '/':
                if (start + 1 < s.length() && s.charAt(start + 1) == '*') {
                    int end = s.indexOf("*/", start + 2);
                    return end < 0
                            ? Span.open(s.length() - start, Kind.BLOCK_COMMENT, '\0', false)
                            : Span.of(end + 2 - start, Kind.BLOCK_COMMENT);
                }
                return null;
            default:
                return null;
        }
    }

    /**
     * {@code $$ … $$} bzw. {@code $tag$ … $tag$}. Der schliessende Tag muss
     * derselbe sein - {@code $a$ … $b$} schliesst nicht, sonst wuerde der Rest
     * als Code formatiert und der Fremd-Delimiter beschaedigt.
     *
     * <p>Absichtlich kein Match auf {@code $1}: ein Tag muss mit Buchstabe oder
     * Unterstrich beginnen, damit PL/pgSQL-Parameter keine Literale oeffnen.
     */
    private static Span dollarQuotedSpan(String s, int start) {
        int tagEnd = dollarTagEnd(s, start);
        if (tagEnd < 0) {
            return null;
        }
        String tag = s.substring(start, tagEnd + 1);
        int end = s.indexOf(tag, tagEnd + 1);
        if (end < 0) {
            return Span.open(s.length() - start, Kind.STRING, '$', false);
        }
        return new Span(end + tag.length() - start, true, Kind.STRING, '$', false);
    }

    /** Ende des einleitenden {@code $$} bzw. {@code $tag$}, oder -1. */
    private static int dollarTagEnd(String s, int start) {
        if (start + 1 < s.length() && s.charAt(start + 1) == '$') {
            return start + 1;
        }
        int i = start + 1;
        if (i >= s.length() || !isTagStart(s.charAt(i))) {
            return -1;
        }
        while (i < s.length() && isTagPart(s.charAt(i))) {
            i++;
        }
        return i < s.length() && s.charAt(i) == '$' ? i : -1;
    }

    private static boolean isTagStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isTagPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /**
     * {@code E'…'} / {@code e'…'}. Der {@code E}-Marker gehoert zum Literal,
     * damit {@code E'it\'s'} nicht am {@code \'} geschlossen wird. Ein
     * Bezeichner wie {@code some_e'x'} matcht nicht, weil das {@code e} dann
     * kein eigener Marker ist.
     */
    private static Span escapeStringSpan(String s, int start) {
        if (start + 1 >= s.length() || s.charAt(start + 1) != '\'') {
            return null;
        }
        Span inner = quotedSpan(s, start + 1, '\'', Kind.STRING, true);
        return new Span(inner.length() + 1, inner.closed(), Kind.STRING, '\'', true);
    }

    /**
     * Behandelt das verdoppelte Escapezeichen ('' bzw. "" bzw. ``). Bei
     * {@code backslashEscapes} zaehlt zusaetzlich {@code \'}.
     */
    private static Span quotedSpan(String s, int start, char quote, Kind kind, boolean backslashEscapes) {
        int i = start + 1;
        while (i < s.length()) {
            if (backslashEscapes && s.charAt(i) == '\\' && i + 1 < s.length()) {
                i += 2;
                continue;
            }
            if (s.charAt(i) == quote) {
                if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return new Span(i - start + 1, true, kind, quote, backslashEscapes);
            }
            i++;
        }
        // Unterminiertes Literal: Rest als geschützt, Fortsetzung in Folgezeile.
        return Span.open(s.length() - start, kind, quote, backslashEscapes);
    }

    private static int lineCommentLength(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\n' || s.charAt(i) == '\r') {
                return i - start;
            }
        }
        return s.length() - start;
    }

    /**
     * Endet der Text in einem unterminierten geschützten Bereich, und wenn ja,
     * welcher Art? Ein unterminierter Bereich reicht per Konstruktion immer
     * bis zum Zeilenende, deshalb genügt diese Prüfung für den Zustand
     * "geht in der Folgezeile weiter".
     *
     * <p>Die Art wird mitgeliefert, weil {@code inLiteral} und
     * {@code inBlockComment} zwei verschiedene Zustände sind: ein
     * unterminiertes {@code /*} mitten in einer Zeile ist kein Literal.
     */
    private static Span openSpanAtEnd(String text, int from) {
        int i = from;
        while (i < text.length()) {
            Span span = protectedSpan(text, i);
            if (span == null) {
                i++;
            } else if (!span.closed()) {
                return span;
            } else {
                i += span.length();
            }
        }
        return null;
    }

    private static String normalizeCode(String code) {
        String collapsed = WHITESPACE_RUN.matcher(code).replaceAll(" ");

        Matcher matcher = KEYWORDS.matcher(collapsed);
        StringBuilder sb = new StringBuilder(collapsed.length());
        while (matcher.find()) {
            String keyword = WHITESPACE_RUN.matcher(matcher.group()).replaceAll(" ").toUpperCase(Locale.ROOT);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(keyword));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Klammerbalance außerhalb von Literalen, bei ";" zurück auf 0. */
    private static int nextDepth(String line, int depth) {
        int current = depth;
        int i = 0;
        while (i < line.length()) {
            Span span = protectedSpan(line, i);
            if (span == null) {
                char c = line.charAt(i);
                if (c == '(') {
                    current++;
                } else if (c == ')') {
                    current = Math.max(0, current - 1);
                } else if (c == ';') {
                    current = 0;
                }
                i++;
            } else {
                i += span.length();
            }
        }
        return current;
    }
}
