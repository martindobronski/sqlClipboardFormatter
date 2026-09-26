package signaliduna;

/**
 * Erkennt die Stellen im SQL-Quelltext, die <em>nicht</em> als Code behandelt
 * werden duerfen: Literale, quotierte Bezeichner und Kommentare.
 *
 * <p>Ausgelagert aus {@link SqlFormatService}, weil inzwischen zwei Stellen
 * dieselbe Antwort brauchen: der Fallback-Formatter (dort darf ein Bereich
 * niemals umgebaut werden) und die Syntaxhervorhebung (dort darf ein Bereich
 * nicht als Keyword eingefaerbt werden). Zwei getrennte Tokenisierungen waeren
 * die wahrscheinlichste Quelle fuer genau den Fehler, den dieses Projekt
 * gerade behebt - {@code SELECT} in einer Zeichenkette faerben.
 *
 * <p>Die Erkennung ist bewusst zustandsbehaftet statt regexbasiert, weil ein
 * Kommentar und ein Literal textlich nicht unterscheidbar sind und beide
 * einen Apostroph bzw. ein Sternchen enthalten koennen.
 */
final class SqlTextSpans {

    /**
     * Art des geschuetzten Bereichs. {@link #QUOTED_IDENT} steht bewusst neben
     * {@link #STRING}: fuer den Formatter ist beides unveraenderlich, fuer die
     * Hervorhebung aber unterschiedlich einzufaerben.
     */
    enum Kind {
        STRING,
        QUOTED_IDENT,
        LINE_COMMENT,
        BLOCK_COMMENT;

        boolean isLiteral() {
            return this == STRING || this == QUOTED_IDENT;
        }
    }

    /**
     * Ein geschuetzter Bereich im Quelltext. {@link #closed()} ist
     * {@code false}, wenn der Bereich unterminiert ist und sich auf die
     * Folgezeilen fortsetzt.
     *
     * @param kind      welche Art von Bereich es ist
     * @param quote     der einleitende Delimiter, {@code '\0'} bei Kommentaren
     * @param backslash ob {@code \} im Bereich ein Escapezeichen ist (nur E'..')
     */
    record Span(int length, boolean closed, Kind kind, char quote, boolean backslash) {

        static Span of(int length, Kind kind) {
            return new Span(length, true, kind, '\0', false);
        }

        static Span open(int length, Kind kind, char quote, boolean backslash) {
            return new Span(length, false, kind, quote, backslash);
        }
    }

    private SqlTextSpans() {
    }

    /**
     * Geschuetzter Bereich ab {@code start}, oder {@code null} wenn dort Code
     * beginnt. Erkannt werden einfache Literale ('..'), Escape-Literale
     * (E'..'), Dollar-Quoting ($$..$$, $tag$..$tag$), quotierte Bezeichner
     * ("..", `..`, [..]) sowie Zeilen- und Blockkommentare.
     */
    static Span protectedSpan(String s, int start) {
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
                // E'...' ist ein Escape-Literal, in dem \' die Quote ueberlebt.
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
        // Unterminiertes Literal: Rest als geschuetzt, Fortsetzung in Folgezeile.
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
}
