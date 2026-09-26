package signaliduna;

import java.awt.Color;

/**
 * Farbensatz eines Themes. Zwei Instanzen: {@link #DUNKEL} und {@link #HELL}.
 *
 * <p>Getrennte Klassen waeren hier falsch: die Felder muessen in beiden
 * Paletten belegt sein, und eine fehlende Farbe faellt beim Wechsel erst zur
 * Laufzeit auf. So erzwingt der Compiler die Vollstaendigkeit.
 *
 * <p>Jedes Textpaar erfuellt WCAG AA (4,5:1); die Werte stehen in
 * {@code FormatterPanelTest}, wo sie unabhaengig nachgerechnet werden - der
 * Test benutzt bewusst eine eigene Formel und greift nicht auf diese Klasse
 * zurueck, sonst pruefte er sich selbst.
 */
final class Theme {

    /** Rolle einer Statusmeldung, bestimmt Farbe und Lebensdauer. */
    enum Rolle {
        NEUTRAL,
        ERFOLG,
        WARNUNG,
        FEHLER
    }

    private final String bezeichnung;
    private final boolean dunkel;

    final Color hintergrund;
    final Color flaeche;
    final Color rand;
    final Color text;
    final Color gedaempft;

    final Color akzent;
    final Color akzentHover;
    final Color aufAkzent;
    final Color sekundaer;
    final Color sekundaerHover;
    final Color aufSekundaer;
    final Color deaktiviert;
    final Color aufDeaktiviert;

    final Color erfolg;
    final Color warnung;
    final Color fehler;

    final Color keyword;
    final Color stringFarbe;
    final Color zahl;
    final Color kommentar;
    final Color bezeichner;
    final Color operator;

    private Theme(String bezeichnung, boolean dunkel, Color hintergrund, Color flaeche, Color rand,
            Color text, Color gedaempft, Color akzent, Color akzentHover, Color aufAkzent,
            Color sekundaer, Color sekundaerHover, Color aufSekundaer, Color deaktiviert,
            Color aufDeaktiviert, Color erfolg, Color warnung, Color fehler, Color keyword,
            Color stringFarbe, Color zahl, Color kommentar, Color bezeichner, Color operator) {
        this.bezeichnung = bezeichnung;
        this.dunkel = dunkel;
        this.hintergrund = hintergrund;
        this.flaeche = flaeche;
        this.rand = rand;
        this.text = text;
        this.gedaempft = gedaempft;
        this.akzent = akzent;
        this.akzentHover = akzentHover;
        this.aufAkzent = aufAkzent;
        this.sekundaer = sekundaer;
        this.sekundaerHover = sekundaerHover;
        this.aufSekundaer = aufSekundaer;
        this.deaktiviert = deaktiviert;
        this.aufDeaktiviert = aufDeaktiviert;
        this.erfolg = erfolg;
        this.warnung = warnung;
        this.fehler = fehler;
        this.keyword = keyword;
        this.stringFarbe = stringFarbe;
        this.zahl = zahl;
        this.kommentar = kommentar;
        this.bezeichner = bezeichner;
        this.operator = operator;
    }

    static final Theme DUNKEL = new Theme(
            "Dunkel", true,
            c(0x1E2024), c(0x262A2F), c(0x383C42), c(0xE8EAED), c(0x9AA4B0),
            c(0x1D4ED8), c(0x2E5FD9), c(0xFFFFFF),
            c(0x31363C), c(0x3B424B), c(0xD6DAE0), c(0x2A2E34), c(0x9AA4B0),
            c(0x4ADE80), c(0xFBBF24), c(0xF87171),
            c(0xBB9AF7), c(0x9ECE6A), c(0xE0AF68), c(0x8B949E), c(0x7DCFFF), c(0xC6C8D1));

    static final Theme HELL = new Theme(
            "Hell", false,
            c(0xF4F5F7), c(0xFFFFFF), c(0xD5D9DE), c(0x1F2328), c(0x5B6470),
            c(0x1D4ED8), c(0x1A44B8), c(0xFFFFFF),
            c(0xE5E8EC), c(0xD8DCE1), c(0x1F2328), c(0xECEEF1), c(0x5B6470),
            c(0x15803D), c(0xB45309), c(0xB91C1C),
            c(0x8250DF), c(0x0F7B3C), c(0x9A3412), c(0x6B7280), c(0x0F766E), c(0x374151));

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    String bezeichnung() {
        return bezeichnung;
    }

    boolean isDunkel() {
        return dunkel;
    }

    Color farbeFuer(Rolle rolle) {
        return switch (rolle) {
            case NEUTRAL -> gedaempft;
            case ERFOLG -> erfolg;
            case WARNUNG -> warnung;
            case FEHLER -> fehler;
        };
    }

    /**
     * Erfolg verschwindet von selbst, Warnung und Fehler nicht: die Meldung
     * "Datenverlust moeglich" darf nicht un gesehen weglaufen, weil der Nutzer
     * kurz zur Zwischenablage geschaut hat.
     */
    static boolean istAusblendbar(Rolle rolle) {
        return rolle == Rolle.ERFOLG;
    }
}
