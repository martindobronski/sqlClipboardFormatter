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
    /**
     * Linie um die gefuellten Schaltflaechen. Im dunklen Theme allein
     * ausreichend: Bernstein liegt auf dem Hintergrund bei nur 2,3:1.
     */
    final Color rahmen;
    final Color text;
    final Color gedaempft;

    /**
     * Die eine Akzentfarbe und ihre beiden Abstufungen. Drei Knöpfe in drei
     * verschiedenen Farbtönen sahen willkürlich aus; ein Farbton mit den
     * Stufen voll, umrandet und getönt liest sich als ein System.
     */
    final Color akzent;
    final Color akzentHover;
    final Color aufAkzent;
    /** Blau als Beschriftung und als Linie auf heller Flaeche. */
    final Color akzentText;
    /** Fuellung der getoenten Stufe. */
    final Color tonal;
    final Color tonalHover;
    final Color aufDeaktiviert;
    /** Wie weit {@link #gesperrt} die Eigenfarbe in den Hintergrund mischt. */
    private final double sperrAnteil;

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
            Color rahmen, Color text, Color gedaempft, Color akzent, Color akzentHover, Color aufAkzent,
            Color akzentText, Color tonal, Color tonalHover,
            Color aufDeaktiviert, double sperrAnteil,
            Color erfolg, Color warnung, Color fehler, Color keyword,
            Color stringFarbe, Color zahl, Color kommentar, Color bezeichner, Color operator) {
        this.bezeichnung = bezeichnung;
        this.dunkel = dunkel;
        this.hintergrund = hintergrund;
        this.flaeche = flaeche;
        this.rand = rand;
        this.rahmen = rahmen;
        this.text = text;
        this.gedaempft = gedaempft;
        this.akzent = akzent;
        this.akzentHover = akzentHover;
        this.aufAkzent = aufAkzent;
        this.akzentText = akzentText;
        this.tonal = tonal;
        this.tonalHover = tonalHover;
        this.aufDeaktiviert = aufDeaktiviert;
        this.sperrAnteil = sperrAnteil;
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
            c(0x1E2024), c(0x262A2F), c(0x383C42), c(0x767C85), c(0xE8EAED), c(0x9AA4B0),
            c(0x1D4ED8), c(0x1A44B8), c(0xFFFFFF),
            c(0x93C5FD), c(0x1E3050), c(0x27395C),
            c(0x9AA4B0), 0.78,
            c(0x4ADE80), c(0xFBBF24), c(0xF87171),
            c(0xBB9AF7), c(0x9ECE6A), c(0xE0AF68), c(0x8B949E), c(0x7DCFFF), c(0xC6C8D1));

    static final Theme HELL = new Theme(
            "Hell", false,
            c(0xF4F5F7), c(0xFFFFFF), c(0xD5D9DE), c(0x6B7280), c(0x1F2328), c(0x5B6470),
            c(0x1D4ED8), c(0x1A44B8), c(0xFFFFFF),
            c(0x1D4ED8), c(0xE8EFFC), c(0xD8E4FB),
            c(0x464E58), 0.70,
            c(0x15803D), c(0xB45309), c(0xB91C1C),
            c(0x8250DF), c(0x0F7B3C), c(0x9A3412), c(0x6B7280), c(0x0F766E), c(0x374151));

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    /**
     * Die Eigenfarbe einer gesperrten Flaeche, so weit zum Hintergrund
     * gemischt, dass sie als inaktiv gilt. Beim Start sind zwei der drei
     * Aktionsknoepfe gesperrt; neutrales Grau raete dort, der Knopf gehoere
     * zu einer anderen Funktion.
     */
    Color gesperrt(Color farbe) {
        return new Color(
                (int) Math.round(farbe.getRed() * (1 - sperrAnteil)
                        + hintergrund.getRed() * sperrAnteil),
                (int) Math.round(farbe.getGreen() * (1 - sperrAnteil)
                        + hintergrund.getGreen() * sperrAnteil),
                (int) Math.round(farbe.getBlue() * (1 - sperrAnteil)
                        + hintergrund.getBlue() * sperrAnteil));
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
