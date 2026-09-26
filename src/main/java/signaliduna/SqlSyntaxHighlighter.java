package signaliduna;

import java.util.Set;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Faerbt SQL im Textfeld ein.
 *
 * <p>Entscheidend ist, dass die Zerlegung dieselbe ist wie beim Formatter:
 * {@link SqlTextSpans} entscheidet, was Literal oder Kommentar ist, und nur
 * der Rest wird auf Keywords, Zahlen und Operatoren geprueft. Eine
 * regexbasierte Eigenloesung wuerde {@code 'bitte select'} faerben und
 * {@code E'it\'s'} an der falschen Stelle schliessen - beides sieht sofort
 * nach Spielzeug aus.
 */
final class SqlSyntaxHighlighter {

    /**
     * Oberhalb dieser Zeichenzahl wird nicht mehr eingefaerbt. Die Zerlegung
     * ist linear, aber jeder Tastendruck laeuft ueber sie; bei einem
     * SQL-Dump von mehreren MB waere die Verzoegerung spuerbar.
     */
    private static final int MAX_ZEICHEN = 100_000;

    private static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "and", "or", "not", "in", "is", "null", "having",
            "join", "on", "using", "as", "into", "update", "set", "values", "like", "ilike",
            "desc", "asc", "distinct", "all", "except", "intersect", "limit", "offset", "union",
            "case", "when", "then", "else", "end", "exists", "between", "recursive", "lateral",
            "create", "alter", "drop", "truncate", "table", "view", "index", "with", "returning",
            "cast", "collate", "over", "partition", "window", "rows", "range", "nulls",
            "merge", "replace", "explain", "analyze", "declare", "begin", "commit", "rollback",
            "grant", "revoke", "vacuum", "call", "show", "use", "exec", "execute", "only",
            "default", "references", "constraint", "primary", "foreign", "fetch", "insert",
            "delete", "group", "by", "order", "key", "inner", "cross", "full", "outer",
            "natural", "left", "right", "top");

    private static final String OPERATOREN = "+-*/%<>=!~^&|:?@#";

    /** Nur fuer den Drift-Test gegen die Keyword-Liste des Formatierers. */
    static Set<String> keywords() {
        return KEYWORDS;
    }

    private final Theme theme;

    SqlSyntaxHighlighter(Theme theme) {
        this.theme = theme;
    }

    /** Faerbt das gesamte Dokument neu ein. Setzt zuerst die Grundfarbe. */
    void faerben(StyledDocument document) {
        String text;
        try {
            text = document.getText(0, document.getLength());
        } catch (javax.swing.text.BadLocationException ex) {
            return;
        }
        int laenge = Math.min(text.length(), MAX_ZEICHEN);

        document.setCharacterAttributes(0, document.getLength(), grund(), false);

        int i = 0;
        while (i < laenge) {
            SqlTextSpans.Span span = SqlTextSpans.protectedSpan(text, i);
            if (span != null && span.length() > 0) {
                setze(document, i, Math.min(span.length(), laenge - i), stilFuer(span.kind()));
                i += span.length();
                continue;
            }
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                int ende = endeZahl(text, i, laenge);
                setze(document, i, ende - i, stil(theme.zahl, false));
                i = ende;
            } else if (isWortzeichen(c)) {
                int ende = endeWort(text, i, laenge);
                if (KEYWORDS.contains(text.substring(i, ende).toLowerCase(java.util.Locale.ROOT))) {
                    setze(document, i, ende - i, stil(theme.keyword, false));
                }
                i = ende;
            } else if (OPERATOREN.indexOf(c) >= 0) {
                int ende = i;
                while (ende < laenge && OPERATOREN.indexOf(text.charAt(ende)) >= 0) {
                    ende++;
                }
                setze(document, i, ende - i, stil(theme.operator, false));
                i = ende;
            } else {
                i++;
            }
        }
    }

    private SimpleAttributeSet grund() {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, theme.text);
        StyleConstants.setBackground(set, theme.flaeche);
        return set;
    }

    private SimpleAttributeSet stilFuer(SqlTextSpans.Kind kind) {
        return switch (kind) {
            case STRING -> stil(theme.stringFarbe, false);
            case QUOTED_IDENT -> stil(theme.bezeichner, false);
            case LINE_COMMENT, BLOCK_COMMENT -> stil(theme.kommentar, true);
        };
    }

    private static SimpleAttributeSet stil(java.awt.Color farbe, boolean kursiv) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, farbe);
        StyleConstants.setItalic(set, kursiv);
        return set;
    }

    private static void setze(StyledDocument document, int start, int laenge, SimpleAttributeSet stil) {
        if (laenge > 0) {
            document.setCharacterAttributes(start, laenge, stil, false);
        }
    }

    private static boolean isWortzeichen(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int endeWort(String text, int start, int laenge) {
        int i = start;
        while (i < laenge && isWortzeichen(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Zahl inklusive Punkt und Exponent, damit {@code 1.5e3} nicht zerschnitten wird. */
    private static int endeZahl(String text, int start, int laenge) {
        int i = start;
        while (i < laenge && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
            i++;
        }
        if (i < laenge && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < laenge && (text.charAt(j) == '+' || text.charAt(j) == '-')) {
                j++;
            }
            if (j < laenge && Character.isDigit(text.charAt(j))) {
                while (j < laenge && Character.isDigit(text.charAt(j))) {
                    j++;
                }
                i = j;
            }
        }
        return i;
    }
}
