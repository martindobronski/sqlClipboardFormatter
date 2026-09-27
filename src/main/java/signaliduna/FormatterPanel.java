package signaliduna;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.JTextPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;
import javax.swing.border.BevelBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;

/**
 * Oberflaeche: SQL aus der Zwischenablage lesen, formatieren, zurueckschreiben.
 *
 * <p>Das {@code SwingWorker}-Muster und die Freischaltung ueber
 * {@link SqlDetector} sind bewusst unveraendert geblieben; geaendert hat sich
 * die Darstellung. Die Logik sitzt weiterhin in {@link SqlPrettyFormatter}
 * und {@link SqlFormatService}, damit sie ohne Fenster testbar bleibt.
 */
public final class FormatterPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String VERSION_DATATEI = "/version.properties";

    /**
     * Deutsches Datumsformat fuer die Versionszeile. Fest als Konstante,
     * nicht ueber {@code Locale.getDefault()}: die Anzeige soll in jedem
     * System gleich aussehen.
     */
    private static final DateTimeFormatter DATUM_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final int BUTTON_GAP = 8;

    /**
     * Oberhalb dieser Zeichenzahl wird die Dialektpruefung abgebrochen: sie
     * laeuft sechs Formatierungen und blockiert sonst das Tippen.
     */
    private static final int DIALECT_PRUEFUNG_MAX_ZEICHEN = 4_000;

    /**
     * Zeilenhoehe des Textfeldes. Bewusst klein: das Fenster wird mit
     * {@code pack()} auf den Inhalt geschaetzt, und eine feste Pixelhoehe von
     * 420 px hat daraus ein Fenster mit 600 px Hoehe und einer halbleeren
     * Flaeche gemacht.
     */
    private static final int EDITOR_ZEILEN = 16;

    /** Eckenradius der Knoepfe und des Theme-Schalters. */
    private static final int ECKE = 9;

    /** Wie lange eine Erfolgsmeldung stehen bleibt, bevor sie verschwindet. */
    private static final int TOAST_MILLIS = 4000;

    /**
     * Verzoegerung der Einfaerbung. Beim Tippen soll nicht jeder Tastendruck
     * sofort die ganze Zeile umzeichnen.
     */
    private static final int HERVORHEBUNG_MILLIS = 120;

    /**
     * Die Funktion einer Schaltflaeche. Die Farbe benennt nicht mehr die
     * Funktion, sondern die Stufe: alle drei Aktionen teilen sich eine
     * Akzentfarbe und unterscheiden sich ueber voll, umrandet und getoent.
     */
    private enum Aktion {
        FORMATIEREN(Glyphe.ZAUBERSTAB),
        LESEN(Glyphe.KLEMMBRETT),
        SCHREIBEN(Glyphe.PFEIL),
        LINK(null);

        private final Glyphe glyphe;

        Aktion(Glyphe glyphe) {
            this.glyphe = glyphe;
        }
    }

    /**
     * Was ein Symbol zeigt. Eigenes Enum statt zweier zusaetzlicher Werte in
     * {@link Aktion}: Mond und Sonne sind keine Aktion, und der Theme-
     * Schalter laeuft nicht durch die Aktionsformatierung.
     */
    private enum Glyphe {
        KLEMMBRETT,
        ZAUBERSTAB,
        PFEIL,
        MOND,
        SONNE
    }

    /** Wie stark eine Aktionsschaltflaeche in die Flaeche greift. */
    private enum Stil {
        VOLL,
        UMRANDET,
        GETOENT
    }

    /**
     * Das Theme, mit dem die App startet. An einer Stelle festgeschrieben,
     * damit Starter und Panel nicht auseinanderlaufen koennen.
     */
    static final Theme STANDARD = Theme.HELL;

    /**
     * Das gerade aktive Theme. Statisch, weil das LookAndFeel selbst global
     * ist: ein zweites Fenster mit einer anderen Palette ergaebe zwei
     * widersprechende Bedienoberflaechen in derselben JVM.
     */
    private static Theme aktuellesTheme = STANDARD;

    /**
     * Das Textfeld. Als {@link JTextPane}, weil {@link JTextArea} keine
     * eingefaerbten Bereiche kennt - die Einfaerbung braucht zwingend ein
     * {@link javax.swing.text.StyledDocument}.
     *
     * <p>Zwei Abweichungen von {@link JTextPane} sind Absicht: Der Text bricht
     * <em>nicht</em> um, weil ein umgebrochener SQL-Schluessel nicht mehr als
     * Schluessel erkennbar ist, und die Fenstergroesse wird ueber eine feste
     * Zeilenzahl gesteuert, weil die Standard-PreferredSize von {@code JTextPane}
     * den <em>gesamten</em> Text umschliesst - {@code pack()} wuerde daraus ein
     * Fenster in der Groesse des SQL-Dumps machen.
     */
    /**
     * Wie {@link LineBorder}, aber mit einem Radius, der wirklich zu sehen ist.
     * Die runden Ecken aus {@code BorderFactory} messen zwei Pixel und
     * fallen deshalb nicht auf. Weiterhin ein {@code LineBorder}, damit die
     * Farbe so auslesbar bleibt wie bei den uebrigen Rahmen.
     */
    private static final class RundeLinie extends LineBorder {
        private final int radius;

        RundeLinie(Color farbe, int radius) {
            super(farbe, 1, false);
            this.radius = radius;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int breite, int hoehe) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(getLineColor());
            g2.draw(new RoundRectangle2D.Float(x, y, breite - 1f, hoehe - 1f, radius, radius));
            g2.dispose();
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }

    /**
     * Das Textfeld mit den Zeilennummern am linken Rand, wie in einem
     * Code-Editor.
     *
     * <p>Entscheidend ist, dass die Zahlen vom Textfeld selbst gezeichnet werden
     * und zwar in dem Innenabstand, den der Text ohnehin hat. Eine eigene Spalte
     * daneben waere eine zweite Komponente: die muesste bei jedem Tastendruck
     * ausdruecklich mitgerufen werden, sonst zeigte sie weiter die Zahlen des
     * aelteren Textes - und sie haette eigene Roll- und Clipkoordinaten, in denen
     * sie gegenueber dem Text ins Ruecken kaeme. Beides ist hier nicht moeglich,
     * weil Zahl und Text aus demselben View in dasselbe Bild gezeichnet werden.
     *
     * <p>Der Text bricht weich um, sonst waere ein umgebrochener SQL-Schluessel
     * nicht mehr als Schluessel erkennbar, und die Breite steht fest, damit das
     * Textfeld beim Tippen nicht springt. Die Zeilennummern zaehlen daher
     * Bildschirmzeilen und nicht Absaetze: eine umbrochene Zeile belegt mehrere
     * Bildschirmzeilen, und jede davon gehoert zu genau einer Zahl.
     */
    private static final class Editor extends JTextPane {

        private static final long serialVersionUID = 1L;

        /** Platz zwischen der Zahl und dem Text. */
        private static final int ZAHLEN_LUFT = 6;

        /** Breite des linken Innenabstands, in dem die Zahlen stehen. */
        private static final int ZAHLEN_RAND = 38;

        private final transient IntConsumer melder;

        private Color zahlenFarbe = Color.GRAY;
        /** Zuletzt gemeldete Bildschirmzeilen, -1 = noch nichts gemeldet. */
        private int gemeldet = -1;
        /** Breite des Textfelds in Pixeln, 0 = so breit wie der Viewport. */
        private int feldBreite;
        /**
         * Der Text-View ist gelegt. Vorher rechnet er noch nicht und liefert
         * darum eine leere BoxView, wenn man ihn nach seiner Hoehe fragt - siehe
         * {@link #textHoehe()}.
         */
        private boolean viewGelegt;

        Editor(IntConsumer melder) {
            this.melder = melder;
            // Ohne Innenabstand klebt die erste Zeile an der Fase des Rahmens.
            // Unten und rechts gehoert der Abstand dem Rollbalken, sonst klebt
            // die letzte Zeile an ihm. Links steht die Zahl.
            setMargin(new Insets(9, ZAHLEN_RAND, 9, 9));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        @Override
        protected void paintComponent(Graphics g) {
            // Der Rahmen ist rund, also muss die Flaeche mitrunden - sonst
            // stehen die eckigen Ecken des Textbereichs ueber dem runden
            // Rahmen hervor.
            Graphics2D g2 = (Graphics2D) g.create();
            g2.clip(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 13f, 13f));
            super.paintComponent(g2);
            // Erst jetzt ist der Text-View gelegt: Super.paintComponent setzt
            // seine Groesse auf das sichtbare Rechteck und zeichnet ihn. Die
            // Zeilen stehen damit an genau den Stellen, an denen auch der Text
            // gerade gezeichnet wurde.
            viewGelegt = true;
            zeichneZeilennummern(g2);
            g2.dispose();
        }

        /**
         * Die Zahlen links in den Innenabstand zeichnen.
         *
         * <p>Gezogen wird in denselben Koordinaten wie der Text, mit derselben
         * Grundlinie ({@code Zeilenanfang + Zeilenabstand der Schrift}). Damit kann
         * die Zahl nicht von der Zeile wegrutschen, egal wie viele Zeilen der
         * Umbruch unter ihr macht. Gezeichnet wird nur, was im Bild steht - der
         * Rest waere ohnehin abgeschnitten.
         */
        private void zeichneZeilennummern(Graphics2D g2) {
            View wurzel = getUI().getRootView(this);
            if (wurzel == null) {
                return;
            }
            List<View> zeilen = new ArrayList<>();
            sammleZeilen(wurzel, zeilen);
            if (zeilen.size() != gemeldet) {
                gemeldet = zeilen.size();
                // Nur hier weiss man, wie viele Zeilen der Text wirklich belegt:
                // der Umbruch gehoert zum Text-View, im Dokument steht eine
                // umbrochene Zeile nur einmal.
                melder.accept(Integer.valueOf(zeilen.size()));
            }
            if (zeilen.isEmpty()) {
                return;
            }
            int aufsteig = g2.getFontMetrics(getFont()).getAscent();
            g2.setFont(getFont());
            g2.setColor(zahlenFarbe);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics schrift = g2.getFontMetrics();
            // Der Innenabstand gehoert den Zahlen: eine zu breite Zahl wird
            // abgeschnitten, statt den Text zu ueberdecken.
            Shape vorher = g2.getClip();
            g2.clip(new Rectangle(0, 0, ZAHLEN_RAND, getHeight()));
            for (int i = 0; i < zeilen.size(); i++) {
                int y = zeilenY(zeilen.get(i));
                if (y < 0) {
                    continue;
                }
                if (y >= getHeight()) {
                    break;
                }
                if (y + aufsteig < 0) {
                    continue;
                }
                String zahl = Integer.toString(i + 1);
                g2.drawString(zahl, ZAHLEN_RAND - ZAHLEN_LUFT - schrift.stringWidth(zahl),
                        y + aufsteig);
            }
            g2.setClip(vorher);
        }

        /**
         * Sammelt die Zeilen-Views des Textes von oben nach unten.
         *
         * <p>Innerhalb eines Absatzes sind die Zeilen genau seine unmittelbaren
         * Kinder: ein Absatz, der laenger ist als das Textfeld, hat davon
         * mehrere - einen je Bildschirmzeile, und jeder bekommt eine Zahl. In die
         * Woerter darunter steigt man nicht, die gehoeren zur Zeile darueber.
         *
         * <p>Ein Absatz ohne Kinder ist die leere Zeile, etwa hinter dem letzten
         * Absatz oder eine Leerzeile im Text. Die zaehlt als eine Zeile mit,
         * sonst fehlt dort eine Zahl.
         */
        private static void sammleZeilen(View view, List<View> ziel) {
            for (int i = 0; i < view.getViewCount(); i++) {
                View kind = view.getView(i);
                if (kind == null) {
                    continue;
                }
                if (istZeile(kind)) {
                    ziel.add(kind);
                } else {
                    sammleZeilen(kind, ziel);
                }
            }
        }

        /**
         * Ist das eine Bildschirmzeile? Ja, wenn darunter nur noch einzelne
         * Woerter liegen, denn die gehoeren zur Zeile darueber. Nein, wenn
         * darunter weitere Zeilen liegen - dann ist es ein Absatz, und seine
         * Zeilen sind die Zahl, die man sucht. Eine Zeile ohne Kinder ist die
         * leere Zeile und zaehlt mit.
         */
        private static boolean istZeile(View view) {
            for (int i = 0; i < view.getViewCount(); i++) {
                if (view.getView(i).getViewCount() > 0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Wo die Bildschirmzeile im Bild steht, oder -1, wenn der Text zwischen
         * Lesen und Zeichnen gekuerzt wurde.
         *
         * <p>Gefragt wird das Textfeld selbst, nicht der View von Hand: dessen
         * Antwort ist genau die, nach der auch der Text gerade gezeichnet wurde -
         * gleiche Breite, gleicher Umbruch, gleicher Innenabstand. Ein Aufruf
         * weiter oben im Baum wuerde eine eigene Zaehlung der Zeilen brauchen und
         * genau an ihr scheitern.
         */
        private int zeilenY(View zeile) {
            try {
                Rectangle2D ort = modelToView2D(zeile.getStartOffset());
                return ort == null ? -1 : (int) Math.round(ort.getY());
            } catch (javax.swing.text.BadLocationException ex) {
                // Der Text wurde zwischen Lesen und Zeichnen gekuerzt. Das
                // Neuzeichnen danach holt die Zahl nach.
                return -1;
            }
        }

        /**
         * Wie hoch der Text in dieser Breite wirklich wird.
         *
         * <p>Gefragt wird den Text-View, weil nur er den Umbruch kennt. Er
         * rechnet allerdings erst, wenn er gelegt ist, und wer ihn vorher fragt,
         * bekommt nicht nur eine falsche Zahl, sondern eine kaputte BoxView
         * (ArrayIndexOutOfBoundsException). Vor dem ersten Zeichnen gibt es darum
         * keine Antwort, und der Bereich startet mit seinen
         * {@value #EDITOR_ZEILEN} Zeilen.
         */
        private int textHoehe() {
            if (!viewGelegt) {
                return 0;
            }
            View wurzel = getUI().getRootView(this);
            if (wurzel == null) {
                return 0;
            }
            int inhalt = (int) Math.round(wurzel.getPreferredSpan(View.Y_AXIS));
            Insets rand = getMargin();
            return inhalt + rand.top + rand.bottom;
        }

        /**
         * Die bevorzugte Groesse: die Breite steht fest, die Hoehe folgt den
         * Zeilen, die der Text wirklich belegt.
         *
         * <p>Ganz wichtig ist, dass die Hoehe nicht festgenagelt wird. Sie ist
         * genau das, was ViewportLayout zum Rechnen braucht: sobald
         * {@link #getScrollableTracksViewportHeight()} 'ja' sagen wuerde, setzt
         * ViewportLayout die Hoehe der Ansicht auf die des Viewports. Der
         * Textbereich waere dann immer so hoch wie das Fenster, ein Rollbalken
         * kaeme nicht zustande, und die Zeilen unterhalb waeren unerreichbar.
         *
         * <p>Unten gelten {@value #EDITOR_ZEILEN} Zeilen als Mindesthoehe, damit
         * ein kurzes SQL nicht in einem niedrigen Kasten steht.
         */
        @Override
        public Dimension getPreferredSize() {
            int zeilenhoehe = getFontMetrics(getFont()).getHeight();
            return new Dimension(feldBreite(), Math.max(EDITOR_ZEILEN * zeilenhoehe, textHoehe()));
        }

        /**
         * Die feste Breite des Textfelds. Nie breiter als der Viewport: sonst
         * entstuende ein waagerechter Rollbalken, und den gibt es hier nicht -
         * lange Zeilen brechen um, sie werden nicht seitlich weggeschoben.
         */
        private int feldBreite() {
            int breite = feldBreite > 0 ? feldBreite : getWidth();
            Container eltern = getParent();
            if (eltern instanceof JViewport viewport) {
                int viewportBreite = viewport.getExtentSize().width;
                if (viewportBreite > 0) {
                    return Math.min(breite, viewportBreite);
                }
            }
            return breite;
        }

        /** Breite in Zeichen, wie bei {@link JTextArea}. */
        void setzeBreiteInZeichen(int zeichen) {
            feldBreite = zeichen * getFontMetrics(getFont()).charWidth('0');
        }

        /**
         * Der Text waechst mit, damit der Rollbalken erscheint und keine Zeile
         * unerreichbar wird. Mehr ist nicht zu tun: die bevorzugte Hoehe in
         * {@link #getPreferredSize()} folgt dem Text-View, und {@code revalidate()}
         * holt sie in das Layout.
         */
        void folgeZeilenzahl() {
            revalidate();
        }

        void setzeZahlenfarbe(Color farbe) {
            this.zahlenFarbe = farbe;
            repaint();
        }
    }

    private final Editor sqlArea = new Editor(this::setzeBildschirmzeilen);

    /** Vom Textfeld gemeldet: so viele Zeilen stehen wirklich im Textfeld. */
    private int bildschirmzeilen;

    private final JLabel statusLabel = new JLabel();
    /** Zeilen- und Zeichenzahl des SQL-Textes, rechts neben der Statuszeile. */
    private final JLabel umfangLabel = new JLabel();
    private final JLabel versionLabel = new JLabel(version());
    private final JLabel dialectLabel = new JLabel("Dialect:");
    private final JLabel dialectWirkung = new JLabel("—");
    private final JPanel header = new JPanel(new BorderLayout());
    private final JPanel editorCard = new JPanel(new BorderLayout());
    private final JScrollPane scrollPane = new JScrollPane(sqlArea);
    private final JPanel footer = new JPanel(new BorderLayout(0, 10));
    private final JPanel dialectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JPanel actionRow = new JPanel(new BorderLayout());
    private final JPanel infoLine = new JPanel(new BorderLayout());
    private final JPanel meldungsFeld = new JPanel(new GridBagLayout());
    private final JButton readButton = createButton("Clipboard einlesen", Aktion.LESEN,
            KeyEvent.VK_L, "Liest SQL aus der Zwischenablage in das Textfeld");
    private final JButton formatButton = createButton("SQL Formatieren", Aktion.FORMATIEREN,
            KeyEvent.VK_F, "Formatiert den Text im Feld");
    private final JButton writeButton = createButton("Ins Clipboard schreiben", Aktion.SCHREIBEN,
            KeyEvent.VK_C, "Schreibt den formatierten Text in die Zwischenablage");
    private final JButton exitButton = createButton("Beenden", Aktion.LINK,
            KeyEvent.VK_B, "Beendet das Programm");
    private final ThemaSchalter themeButton = createThemeSwitch();
    private final JComboBox<SqlDialect> dialectCombo = new JComboBox<>(SqlDialect.values());

    private final Timer dialectTimer;
    private final Timer highlightTimer;
    private final Timer toastTimer;
    private int dialectGeneration;
    private boolean faerbtGerade;
    private boolean busy;
    private Theme.Rolle statusRolle = Theme.Rolle.NEUTRAL;
    private transient SqlSyntaxHighlighter highlighter;

    public FormatterPanel() {

        dialectTimer = new Timer(300, e -> pruefeWirksameDialekte());
        highlightTimer = new Timer(HERVORHEBUNG_MILLIS, e -> faerbeHoch());
        // Ohne this stuenden ausblendende Meldungen dauerhaft und ueberlagerten
        // bei jedem Tastendruck die naechste.
        toastTimer = new Timer(TOAST_MILLIS, e -> setStatus("", Theme.Rolle.NEUTRAL));
        toastTimer.setRepeats(false);
        // Entprellung, keine Wiederholung: nach jeder Aenderung genau einmal
        // neu einfaerben. Als wiederholender Timer wuerde er sich selbst neu
        // starten (siehe Reentranz-Sperre in onTextChanged) und dauerhaft
        // CPU verbrauchen.
        highlightTimer.setRepeats(false);

        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sqlArea.setzeBreiteInZeichen(84);
        // Waagerecht wird nie gebraucht: lange Zeilen brechen um, und die Breite
        // des Textfelds steht fest. Ohne diese Zusage wuerde ein Fenster, das
        // schmaler ist als das Textfeld, seitlich wegrollen.
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Ohne Namen bleibt das Textfeld fuer Screenreader und Sprachsteuerung
        // nur ein Feld ohne Aussage.
        sqlArea.getAccessibleContext().setAccessibleName("SQL-Text");
        sqlArea.getAccessibleContext().setAccessibleDescription(
                "Hier steht das SQL. Ueber die Aktionen wird es formatiert und "
                        + "in die Zwischenablage geschrieben.");
        // Die Beschriftung gehoert zum Auswahlfeld, nicht nur daneben.
        dialectLabel.setLabelFor(dialectCombo);
        dialectCombo.getAccessibleContext().setAccessibleName("SQL-Dialekt");

        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        versionLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        dialectLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dialectWirkung.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dialectCombo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        dialectCombo.setSelectedItem(SqlDialect.STANDARD);
        dialectCombo.setToolTipText("Bestimmt, wie sql-formatter den Text interpretiert.");
        dialectCombo.setPreferredSize(new Dimension(170, 26));
        dialectWirkung.setToolTipText(
                "Fuer den Text im Feld ermittelt: diese Dialekte liefern ein anderes Ergebnis.");
        dialectWirkung.setVisible(false);
        zeigeUmfang();

        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(16, 18, 14, 18));

        themeButton.setToolTipText(aktuellesTheme.isDunkel()
                ? "Auf das helle Theme umschalten"
                : "Auf das dunkle Theme umschalten");
        header.add(themeButton, BorderLayout.EAST);

        editorCard.add(scrollPane, BorderLayout.CENTER);

        dialectRow.add(dialectLabel);
        dialectRow.add(dialectCombo);
        dialectRow.add(dialectWirkung);

        // GridBagLayout statt GridLayout: jede Schaltflaeche bekommt ihre
        // natuerliche Breite. Bei GridLayout(1, 3) wuerden alle auf gleiche
        // Breite gestreckt und "Ins Clipboard schreiben" beschnitten.
        JPanel primaryActions = new JPanel(new GridBagLayout());
        primaryActions.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, BUTTON_GAP);
        for (JButton button : new JButton[]{readButton, formatButton, writeButton}) {
            primaryActions.add(button, gbc);
            gbc.gridx++;
        }
        // Beenden gehoert nicht in die Aktionsgruppe: es ist keine weitere
        // Station auf dem Weg in die Zwischenablage, sondern das Ende der
        // Sitzung. Rechts abgesetzt und als reiner Textlink.
        actionRow.add(primaryActions, BorderLayout.WEST);
        actionRow.add(exitButton, BorderLayout.EAST);

        // Zaehler und Meldung teilen sich einen Platz ganz links. Zwei Texte
        // in derselben Zeile wuerden sich gegenseitig in die Breite schieben,
        // der Zaehler waende dabei sichtbar nach rechts.
        meldungsFeld.setOpaque(false);
        // GridBagLayout, nicht BorderLayout: zwei Komponenten auf dasselbe
        // CENTER zu legen ist kein Ersatz - der zweite add verdraengt den
        // ersten im Layout, und die verdraengte Komponente wird dann gar nicht
        // mehr angeordnet, bleibt also bei Breite 0 und unsichtbar. Das
        // GridBagLayout legt beide in dieselbe Zelle; sichtbar ist immer nur
        // eines von beiden.
        meldungsFeld.add(statusLabel, new GridBagConstraints());
        meldungsFeld.add(umfangLabel, new GridBagConstraints());
        infoLine.add(meldungsFeld, BorderLayout.WEST);
        infoLine.add(versionLabel, BorderLayout.EAST);

        footer.add(dialectRow, BorderLayout.NORTH);
        footer.add(actionRow, BorderLayout.CENTER);
        footer.add(infoLine, BorderLayout.SOUTH);

        add(header, BorderLayout.NORTH);
        add(editorCard, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        readButton.addActionListener(e -> readFromClipboard());
        formatButton.addActionListener(e -> formatSqlText());
        writeButton.addActionListener(e -> writeToClipboard());
        exitButton.addActionListener(e -> exitApplication());
        themeButton.addActionListener(e -> schalteThemeUm());
        dialectCombo.addActionListener(e -> dialectTimer.restart());

        // Ohne diesen Listener bliebe die Freischaltung nach dem Einlesen
        // stehen, sobald der Text von Hand geaendert wurde.
        sqlArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                if (faerbtGerade) {
                    return;
                }
                onTextChanged();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                if (faerbtGerade) {
                    return;
                }
                onTextChanged();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                if (faerbtGerade) {
                    return;
                }
                onTextChanged();
            }
        });

        anwendeTheme();
        refresh();
    }

    /**
     * Der Highlighter haelt nur den aktuellen Farbsatz und laesst sich aus ihm
     * neu erzeugen. Deshalb transient: nach einer Deserialisierung ist das
     * Feld null, statt einen nicht serialisierbaren Verweis mitzuschleppen.
     */
    private SqlSyntaxHighlighter highlighter() {
        if (highlighter == null) {
            highlighter = new SqlSyntaxHighlighter(aktuellesTheme);
        }
        return highlighter;
    }

    /**
     * Setzt das LookAndFeel vor dem ersten Fenster. Muss vor dem Erzeugen der
     * Komponenten laufen, sonst bekommen sie das alte Theme.
     */
    static void setzeTheme(Theme theme) {
        aktuellesTheme = theme;
        try {
            UIManager.setLookAndFeel(theme.isDunkel() ? new FlatDarkLaf() : new FlatLightLaf());
        } catch (UnsupportedLookAndFeelException ex) {
            // Das bisherige LookAndFeel bleibt stehen; die App startet dann eben
            // mit gemischten Farben statt gar nicht.
        }
    }

    /**
     * Ueberträgt das aktuelle Theme auf alle Komponenten. Nach einem
     * LookAndFeel-Wechsel setzt Swing die Komponenten zurueck, deshalb muss
     * das Farbsetzt von Hand erneut durchlaufen.
     */
    private void anwendeTheme() {
        Theme theme = aktuellesTheme;

        setBackground(theme.hintergrund);
        setBorder(new EmptyBorder(16, 18, 14, 18));
        for (JPanel panel : List.of(header, editorCard, footer, dialectRow, actionRow, infoLine)) {
            panel.setBackground(theme.hintergrund);
        }
        // Duenne schwarze Linie plus dieselbe Fase wie an den Buttons, damit
        // das Textfeld und die Knoepfe als dieselbe Familie lesen. Im dunklen
        // Theme ist die schwarze Linie fuer sich nur 1,1:1 - sichtbar bleibt
        // der Rahmen dort ueber die Fase.
        editorCard.setBorder(new CompoundBorder(
                new RundeLinie(Color.BLACK, 14),
                new BevelBorder(BevelBorder.RAISED, theme.rahmen, theme.hintergrund)));

        versionLabel.setForeground(theme.gedaempft);
        umfangLabel.setForeground(theme.gedaempft);
        dialectLabel.setForeground(theme.gedaempft);
        statusLabel.setForeground(theme.farbeFuer(statusRolle));

        // Der Hinweis ist ein Chip, keine Zeile Fliesstext: getoente Flaeche,
        // Akzentfarbe und ein Rahmen, damit die helle Fuellung sich vom
        // Fenster abhebt.
        dialectWirkung.setOpaque(true);
        dialectWirkung.setForeground(theme.akzentText);
        dialectWirkung.setBackground(theme.tonal);
        dialectWirkung.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.rahmen, 1),
                BorderFactory.createEmptyBorder(1, 8, 1, 8)));

        dialectCombo.setBackground(theme.flaeche);
        dialectCombo.setForeground(theme.text);

        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setViewportBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(theme.flaeche);
        sqlArea.setzeZahlenfarbe(theme.gedaempft);
        sqlArea.setBackground(theme.flaeche);
        sqlArea.setForeground(theme.text);
        sqlArea.setCaretColor(theme.text);

        highlighter = new SqlSyntaxHighlighter(theme);
        faerbeHoch();

        // Der Schalter zeigt seinen Zustand selbst; Text und Symbol sagen,
        // wohin ein Klick fuehrt - sonst muss man am Wort raten, was passiert.
        themeButton.setSelected(theme.isDunkel());
        themeButton.setIcon(new Symbol(theme.isDunkel() ? Glyphe.SONNE : Glyphe.MOND));
        themeButton.setIconTextGap(7);
        themeButton.setText(theme.isDunkel() ? "Hell" : "Dunkel");
        themeButton.setzeFarben(theme);
        themeButton.setToolTipText("Auf das " + (theme.isDunkel() ? "helle" : "dunkle")
                + " Theme umschalten");

        for (JButton button : new JButton[]{readButton, formatButton, writeButton, exitButton}) {
            applyButtonColors(button);
        }
    }

    private void schalteThemeUm() {
        setzeTheme(aktuellesTheme.isDunkel() ? Theme.HELL : Theme.DUNKEL);
        anwendeTheme();
        // Der Wechsel des LookAndFeel aendert Rahmen und Schriften der
        // Fremdkomponenten, das Fenster muss im Normalzustand also neu
        // gepackt werden - sonst schneidet der Rahmen die Knöpfe ab.
        Window fenster = SwingUtilities.getWindowAncestor(this);
        if (fenster != null) {
            Rectangle rahmen = fenster.getBounds();
            if (gehoertGepackt(maximiert(fenster), rahmen, bildschirmVon(rahmen))) {
                fenster.pack();
            }
        }
        repaint();
    }

    /**
     * Nur im Normalzustand packen. {@code pack()} nimmt dem Fenster seine
     * Groesse und seine Lage: es setzt beide auf die Vorzugsgroesse und
     * rueckt es nach oben links. Ein maximiertes Fenster verliert dabei
     * seinen Zustand, und aus einem Fenster im echten Vollbild wird ein
     * kleines oben links - es gibt keine Moeglichkeit, das zurueckzuholen.
     *
     * <p>Beide Faelle werden erkannt, ohne sie zuruecksetzen zu muessen: der
     * maximierte ueber den Zustand, den das Fenster selbst meldet, der echte
     * Vollbild darueber, dass das Fenster den Bildschirm fuellt, auf dem es
     * liegt. Ein maximiertes Fenster laesst unter Windows die Taskleiste weg,
     * fuellt den Bildschirm also nie ganz - deshalb reicht die Groesse allein
     * nicht, und deshalb wird gar nicht gepackt statt gepackt und zurueckgesetzt.
     *
     * @param bildschirmRahmen die Grenzen des Bildschirms, auf dem das Fenster liegt
     */
    static boolean gehoertGepackt(boolean maximiert, Rectangle fensterRahmen, Rectangle bildschirmRahmen) {
        return !maximiert && !bildschirmRahmen.equals(fensterRahmen);
    }

    /** Meldet, ob das Fenster sich selbst als maximiert fuehrt. */
    private static boolean maximiert(Window fenster) {
        if (fenster instanceof Frame) {
            int zustand = ((Frame) fenster).getExtendedState();
            return (zustand & Frame.MAXIMIZED_BOTH) != 0;
        }
        return false;
    }

    /**
     * Die Grenzen des Bildschirms, auf dem das Fenster liegt - erkannt an der
     * Mitte des Rahmens, weil ein Fenster ueber zwei Bildschirme ragen kann.
     */
    private static Rectangle bildschirmVon(Rectangle fensterRahmen) {
        GraphicsDevice[] aufbau =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        Point mitte = new Point(fensterRahmen.x + fensterRahmen.width / 2,
                fensterRahmen.y + fensterRahmen.height / 2);
        for (GraphicsDevice geraet : aufbau) {
            Rectangle bild = geraet.getDefaultConfiguration().getBounds();
            if (bild.contains(mitte)) {
                return bild;
            }
        }
        return aufbau[0].getDefaultConfiguration().getBounds();
    }

    /**
     * Faellt eine Aenderung des Textfelds auf. Wird waehrend der Einfaerbung
     * nicht aufgerufen - siehe {@link #faerbeHoch()}.
     */
    /**
     * Zeilen und Zeichen unter dem Textfeld mitzaehlen. Bei einer
     * Formatieraufgabe die einzige Zahl, die man beim Kuerzen im Blick
     * behalten will.
     *
     * <p>Gezahlt werden die Bildschirmzeilen, weil man beim Kuerzen an den
     * Zeilen im Textfeld entlanggeht und nicht an den Absaetzen: eine Zeile,
     * die laenger ist als das Textfeld, bricht um und gehoert dann mehr als
     * einmal mit.
     */
    private void zeigeUmfang() {
        String t = sqlArea.getText();
        if (t.isEmpty()) {
            umfangLabel.setText("0 Zeilen, 0 Zeichen");
            return;
        }
        int zeilen = Math.max(
                sqlArea.getDocument().getDefaultRootElement().getElementCount(), bildschirmzeilen);
        umfangLabel.setText(zeilen + (zeilen == 1 ? " Zeile, " : " Zeilen, ")
                + zaehlbareZeichen(t) + " Zeichen");
    }

    /**
     * Zeichen inklusive der Zeilenumbrueche, aber ohne den Unterschied
     * zwischen den Systemen.
     *
     * <p>Wer den Text einliest, bekommt unter Windows CRLF und unter macOS
     * LF. Zaehlt man die Zeichen im Dokument, zeigt derselbe SQL-Text je
     * nach System zwei Zeichen mehr pro Umbruch - der Zaehler waere dann
     * eine Angabe ueber den Rechner statt ueber den Text. Ein Umbruch
     * zaehlt hier als ein Zeichen, wie im Textfeld zu sehen.
     */
    private static int zaehlbareZeichen(String t) {
        return t.replace("\r\n", "\n").replace('\r', '\n').length();
    }

    /**
     * Vom Zeilenkopf gemeldet, wie viele Bildschirmzeilen der Text belegt. Das
     * weiss nur der Text-View, weil er den Umbruch kennt; im Dokument steht eine
     * umbrochene Zeile trotzdem nur einmal. Bis zum ersten Zeichnen zaehlt es
     * darum die Absaetze - lieber eine zu kleine Zahl als eine erfundene.
     */
    private void setzeBildschirmzeilen(int zeilen) {
        bildschirmzeilen = zeilen;
        zeigeUmfang();
    }

    private void onTextChanged() {
        zeigeUmfang();
        // Muss vor refresh() kommen: sonst rechnet der Rollbalken noch mit der
        // alten Zeilenzahl.
        sqlArea.folgeZeilenzahl();
        refresh();
        dialectTimer.restart();
        highlightTimer.restart();
    }

    /**
     * Faerbt den Text ein, ohne die Aenderungs-Listener erneut anzustossen.
     *
     * <p>Notwendig, weil {@code setCharacterAttributes} selbst Dokument-Events
     * feuert. Ohne diese Sperre laeuft eine Endlosschleife: Einfaerben loest
     * Aenderungs-Events aus, die den Einfaerbe-Timer neu starten, der wieder
     * einfaerbt. Schlimmer noch - der Dialekt-Timer wird dabei im Takt der
     * Einfaerbung neu gestartet und erreicht seine Verzoegerung nie, sodass
     * die Wirkungsanzeige dauerhaft auf "—" stehen bliebe.
     */
    void faerbeHoch() {
        faerbtGerade = true;
        try {
            highlighter().faerben(sqlArea.getStyledDocument());
        } finally {
            faerbtGerade = false;
        }
    }

    /**
     * Ermittelt im Hintergrund, welche Dialekte den aktuellen Text gegenueber
     * Standard SQL veraendern, und zeigt das neben der Auswahlbox an.
     *
     * <p>Das beantwortet die Frage beim Tippen, warum der Schalter manchmal
     * scheinbar nichts tut, ohne einen Dialekt aus der Liste zu entfernen -
     * ein T-SQL-Skript mit {@code TOP} braucht T-SQL auch dann, wenn die
     * letzte Abfrage keine war.
     */
    private void pruefeWirksameDialekte() {
        // Jeder Aufruf macht laufende Pruefungen veraltet - auch die, die
        // gar keine starten. Andernfalls gilt eine noch laufende Pruefung des
        // vorherigen Textes weiter als aktuell und schreibt ihre Antwort
        // spaeter ueber das Ergebnis des leeren Feldes hinweg.
        int generation = ++dialectGeneration;
        String text = sqlArea.getText();
        if (text == null || text.isBlank()) {
            zeigeDialectHinweis("—", null);
            return;
        }
        if (text.length() > DIALECT_PRUEFUNG_MAX_ZEICHEN) {
            zeigeDialectHinweis("—", "Text zu lang, um die Dialekte zu vergleichen.");
            return;
        }

        new SwingWorker<List<SqlDialect>, Void>() {
            @Override
            protected List<SqlDialect> doInBackground() {
                return SqlPrettyFormatter.effectiveDialects(text);
            }

            @Override
            protected void done() {
                if (generation != dialectGeneration) {
                    return;
                }
                List<SqlDialect> wirksam;
                try {
                    wirksam = get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException ex) {
                    zeigeDialectHinweis("—", null);
                    return;
                }
                if (wirksam.isEmpty()) {
                    zeigeDialectHinweis("Standard SQL genügt", null);
                } else {
                    String namen = wirksam.stream()
                            .map(SqlDialect::shortLabel)
                            .collect(Collectors.joining(", "));
                    zeigeDialectHinweis("wirksam: " + namen, null);
                }
            }
        }.execute();
    }

    /**
     * Der Hinweis erscheint nur, wenn er etwas zu sagen hat. "Standard SQL
     * genuegt" und der leere Zustand stehen jetzt im Tooltip des Dropdowns -
     * als Zeile daneben waren sie Bei jedem Formatieren Fuelltext.
     */
    private void zeigeDialectHinweis(String text, String tooltip) {
        boolean meldung = !text.equals("—") && !text.equals("Standard SQL genügt");
        dialectWirkung.setText(text);
        dialectWirkung.setToolTipText(tooltip != null ? tooltip
                : "Fuer den Text im Feld ermittelt: diese Dialekte liefern ein anderes Ergebnis.");
        dialectWirkung.setVisible(meldung);
    }

    private void refresh() {
        boolean valid = !busy && SqlDetector.looksLikeSql(sqlArea.getText());
        formatButton.setEnabled(valid);
        writeButton.setEnabled(valid);
        readButton.setEnabled(!busy);

        if (busy) {
            return;
        }
        if (valid || sqlArea.getText().isBlank()) {
            setStatus("", Theme.Rolle.NEUTRAL);
        } else {
            setStatus("⚠ Der Text sieht nicht nach SQL aus. Formatieren und Schreiben sind blockiert.",
                    Theme.Rolle.WARNUNG);
        }
    }

    /**
     * Setzt die Statuszeile. Erfolgs- und Hinweistexte verschwinden von
     * selbst, Warnungen und Fehler bleiben stehen: die Meldung "Datenverlust
     * moeglich" darf nicht weglaufen, nur weil der Nutzer kurz in eine andere
     * Anwendung geschaut hat.
     */
    private void setStatus(String text, Theme.Rolle rolle) {
        statusRolle = rolle;
        statusLabel.setText(text);
        statusLabel.setForeground(aktuellesTheme.farbeFuer(rolle));
        // Genau einer der beiden ist sichtbar: nur eine echte Meldung
        // verdraengt den Zaehler, im Ruhezustand steht er wieder da.
        boolean meldungDa = !text.isEmpty();
        statusLabel.setVisible(meldungDa);
        umfangLabel.setVisible(!meldungDa);
        boolean ausblenden = !busy && !text.isEmpty() && Theme.istAusblendbar(rolle);
        if (ausblenden) {
            toastTimer.restart();
        } else {
            toastTimer.stop();
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        refresh();
    }

    private void readFromClipboard() {
        setBusy(true);
        setStatus("Lese Zwischenablage ...", Theme.Rolle.NEUTRAL);

        new SwingWorker<ClipboardService.Result, Void>() {
            @Override
            protected ClipboardService.Result doInBackground() {
                return ClipboardService.read();
            }

            @Override
            protected void done() {
                setBusy(false);
                ClipboardService.Result result;
                try {
                    result = get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setStatus("❌ Lesen der Zwischenablage abgebrochen.", Theme.Rolle.FEHLER);
                    return;
                } catch (ExecutionException ex) {
                    setStatus("❌ Fehler beim Lesen der Zwischenablage: " + ex.getCause(),
                            Theme.Rolle.FEHLER);
                    return;
                }

                switch (result.status()) {
                    case OK -> {
                        sqlArea.setText(result.text());
                        sqlArea.setCaretPosition(0);
                        if (SqlDetector.looksLikeSql(result.text())) {
                            setStatus("✓ Text geladen. Formatieren und Schreiben sind freigeschaltet.",
                                    Theme.Rolle.ERFOLG);
                        } else {
                            setStatus("⚠ Text geladen, sieht aber nicht nach SQL aus. Aktionen blockiert.",
                                    Theme.Rolle.WARNUNG);
                        }
                    }
                    case NO_TEXT, EMPTY -> setStatus("⚠ " + result.detail(), Theme.Rolle.WARNUNG);
                    case UNAVAILABLE -> setStatus("❌ " + result.detail(), Theme.Rolle.FEHLER);
                }
            }
        }.execute();
    }

    /**
     * Formatierung laeuft im Hintergrund: sql-formatter ist rund 20x langsamer
     * als die frueherere Normalisierung (1.6 s statt 86 ms bei 2000 Zeilen).
     * Auf dem EDT wuerde das Fenster dabei einfrieren.
     */
    private void formatSqlText() {
        String original = sqlArea.getText();
        if (original.isBlank()) {
            return;
        }

        SqlDialect dialect = (SqlDialect) dialectCombo.getSelectedItem();
        int caret = Math.min(sqlArea.getCaretPosition(), original.length());

        setBusy(true);
        setStatus("Formatiere ...", Theme.Rolle.NEUTRAL);

        new SwingWorker<SqlPrettyFormatter.Result, Void>() {
            @Override
            protected SqlPrettyFormatter.Result doInBackground() {
                return SqlPrettyFormatter.format(original, dialect);
            }

            @Override
            protected void done() {
                setBusy(false);

                SqlPrettyFormatter.Result result;
                try {
                    result = get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setStatus("❌ Formatierung abgebrochen.", Theme.Rolle.FEHLER);
                    return;
                } catch (ExecutionException ex) {
                    setStatus("❌ Fehler beim Formatieren: " + ex.getCause().getMessage(),
                            Theme.Rolle.FEHLER);
                    return;
                }

                sqlArea.setText(result.sql());
                sqlArea.setCaretPosition(Math.min(caret, result.sql().length()));
                // Sofort neu einfaerben, sonst steht der_cursor kurz in der
                // farbe des vorherigen Dialekts.
                faerbeHoch();

                // Nur die Erfolgsmeldung setzen, wenn der Text weiterhin als SQL
                // erkannt wird - sonst gilt die Warnung aus refresh().
                if (SqlDetector.looksLikeSql(result.sql())) {
                    setStatus(successMessage(result), result.bestPractice()
                            ? Theme.Rolle.ERFOLG : Theme.Rolle.NEUTRAL);
                }
            }
        }.execute();
    }

    /** Paketsichtbar fuer Tests, weil der Text die Auswahl des Pfades belegt. */
    static String successMessage(SqlPrettyFormatter.Result result) {
        String notice = result.notice() == null ? "" : " " + result.notice();
        if (result.bestPractice()) {
            return "✓ SQL-Text nach Best Practice formatiert." + notice;
        }
        return "⚠ Nur normalisiert - Zeilenstruktur unverändert." + notice;
    }

    private void writeToClipboard() {
        String text = sqlArea.getText().strip();
        if (text.isEmpty()) {
            setStatus("⚠ Das Textfeld ist leer. Nichts zu kopieren.", Theme.Rolle.WARNUNG);
            return;
        }

        setBusy(true);
        setStatus("Schreibe in die Zwischenablage ...", Theme.Rolle.NEUTRAL);

        Component window = SwingUtilities.getWindowAncestor(this);
        ClipboardOwner owner = window instanceof ClipboardOwner ? (ClipboardOwner) window : null;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                ClipboardService.write(text, owner);
                return null;
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setStatus("❌ Schreiben abgebrochen.", Theme.Rolle.FEHLER);
                    return;
                } catch (ExecutionException ex) {
                    setStatus("❌ Fehler beim Schreiben: " + ex.getCause().getMessage(), Theme.Rolle.FEHLER);
                    return;
                }
                setStatus("✓ SQL erfolgreich in die Zwischenablage kopiert.", Theme.Rolle.ERFOLG);
            }
        }.execute();
    }

    /**
     * Beendet die Anwendung so wie das Schliessen des Fensters: das Fenster wird
     * disposed. Besitzt dieses Panel das Fenster allein, wird danach
     * {@code System.exit(0)} erzwungen - das alleine laesst die JVM unter
     * macOS rund zwoelf Sekunden fuer das Herunterfahren der AWT-Event-Queue
     * stehen, was wie ein Haenger wirkt. In einem fremden Fenster wird
     * dagegen <em>nicht</em> beendet, damit Einbettende nicht mit beendet
     * werden.
     */
    private void exitApplication() {
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window == null) {
            // Kein umgebendes Fenster (z. B. im Test): nur ausblenden, JVM lebt weiter.
            setVisible(false);
            return;
        }
        boolean soleOwner = window instanceof RootPaneContainer rpc
                && isSoleContent(rpc.getContentPane(), this);
        window.dispose();
        if (soleOwner) {
            System.exit(0);
        }
    }

    /**
     * True, wenn das Panel den Fensterinhalt allein bildet - dann ist es die
     * gesamte Anwendung und ein hartes Beenden ist erlaubt.
     *
     * <p>Wichtig: {@code setContentPane(panel)} macht das Panel <em>selbst</em>
     * zum ContentPane. Es hat dann seine eigenen Kinder (Titel, Textbereich,
     * Fusszeile) und {@code getComponentCount()} liefert nicht 1. Deshalb wird
     * die Identitaet des Containers zuerst geprueft.
     */
    static boolean isSoleContent(Container content, Component self) {
        if (content == self) {
            return true;
        }
        if (content.getComponentCount() != 1) {
            return false;
        }
        Component sole = content.getComponent(0);
        return sole == self || SwingUtilities.isDescendingFrom(self, sole);
    }

    /**
     * Ein echter Schalter statt eines Textlinks: der Zustand steckt im
     * Schalter selbst, statt in einem Wort, das man erst lesen muss. Das
     * Label benennt die Einstellung, die Position den Zustand.
     */
    private static ThemaSchalter createThemeSwitch() {
        ThemaSchalter schalter = new ThemaSchalter("Dunkel");
        schalter.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        schalter.setFocusPainted(false);
        schalter.setCursor(new Cursor(Cursor.HAND_CURSOR));
        schalter.setToolTipText("Zwischen hellem und dunklem Theme wechseln");
        return schalter;
    }

    /**
     * Der Theme-Schalter malt sich selbst. FlatLaf zeichnet die Beschriftung
     * eines aktiven Tasters in einer eigenen Farbe und ueberschreibt dabei
     * setForeground - "Hell" stand dann dunkel auf einem mittleren Grau, obwohl
     * die Komponente auf weiss eingestellt war. Auch die Schluessel
     * "ToggleButton.selectedForeground" und ".selectedBackground" aendern daran
     * nichts, deshalb zeichnet der Schalter Flaeche, Symbol und Text selbst.
     * Weiss auf dunkel bzw. Textfarbe auf hell, sonst waere die Beschriftung in
     * einem der beiden Themes unlesbar.
     */
    private static final class AktionsButton extends JButton {

        private static final long serialVersionUID = 1L;

        private Color ring;

        AktionsButton(String text) {
            super(text);
        }

        void setzeRing(Color farbe) {
            ring = farbe;
            repaint();
        }

        /**
         * Zeichnet den Fokusring selbst. FlatLaf laesst ihn entweder ueber den
         * LookAndFeel-Rahmen laufen, den diese Buttons gar nicht benutzen, oder
         * gar nicht zeichnen - {@code paintFocus} der Basisklasse ist leer.
         * Ohne eigenen Ring waeren die vier Aktionen fuer die Tastatur blind.
         */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (ring == null || !hasFocus()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ring);
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f,
                    getWidth() - 3f, getHeight() - 3f, ECKE, ECKE));
            g2.dispose();
        }
    }

    private static final class ThemaSchalter extends JToggleButton {

        private static final long serialVersionUID = 1L;

        private Color flaeche = Color.GRAY;
        private Color hover = Color.LIGHT_GRAY;
        private Color linie = Color.GRAY;
        private Color ring = Color.GRAY;

        ThemaSchalter(String text) {
            super(text);
            // Der Fokusring wird in paintComponent gezeichnet, der LookAndFeel
            // darf ihn nicht noch einmal malen.
            setFocusPainted(false);
            getAccessibleContext().setAccessibleName("Theme-Schalter");
            getAccessibleContext().setAccessibleDescription(
                    "Schaltet zwischen hellem und dunklem Theme um");
        }

        void setzeFarben(Theme theme) {
            flaeche = theme.flaeche;
            hover = theme.rand;
            linie = theme.rahmen;
            ring = theme.akzent;
            setBackground(flaeche);
            setForeground(theme.isDunkel() ? theme.aufAkzent : theme.text);
            setBorder(BorderFactory.createCompoundBorder(
                    new RundeLinie(linie, ECKE),
                    BorderFactory.createEmptyBorder(6, 14, 6, 14)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            ButtonModel modell = getModel();
            g2.setColor(modell.isArmed() || modell.isPressed() ? hover : flaeche);
            g2.fill(new RoundRectangle2D.Float(0f, 0f,
                    getWidth(), getHeight(), ECKE, ECKE));

            // Der Schalter malt sich selbst, also muss er seinen Fokus auch
            // selbst zeigen: mit ausgeschaltetem Fokusrahmen der Tastatur
            // gaebe es gar keinen Hinweis mehr, wo man gerade ist.
            if (hasFocus()) {
                g2.setColor(ring);
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f,
                        getWidth() - 3f, getHeight() - 3f, ECKE, ECKE));
            }

            javax.swing.Icon symbol = getIcon();
            String beschriftung = getText();
            FontMetrics schrift = g2.getFontMetrics();
            int luecke = symbol == null || beschriftung.isEmpty() ? 0 : getIconTextGap();
            int symbolBreite = symbol == null ? 0 : symbol.getIconWidth() + luecke;
            int beschriftungBreite = schrift.stringWidth(beschriftung);
            int start = (getWidth() - symbolBreite - beschriftungBreite) / 2;
            int zeilenHoehe = schrift.getHeight();
            int oben = (getHeight() - zeilenHoehe) / 2;

            if (symbol != null) {
                // Das Symbol nimmt die Vordergrundfarbe des Schalters an.
                symbol.paintIcon(this, g2, start,
                        oben + (zeilenHoehe - symbol.getIconHeight()) / 2);
            }
            g2.setColor(getForeground());
            g2.drawString(beschriftung, start + symbolBreite, oben + schrift.getAscent());
            g2.dispose();
        }
    }

    private JButton createButton(String text, Aktion aktion, int mnemonic, String beschreibung) {
        AktionsButton button = new AktionsButton(text);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        // Ohne Fokusrahmen und Mnemonik bleibt fuer die Tastatur nur noch das
        // blinde Durchprobieren: Der Rahmen macht sichtbar, wo man ist, und das
        // Tastenkuerzel braucht die Taste, um die Beschriftung zu unterstreichen.
        button.setFocusPainted(true);
        // Der eigene Rahmen zeichnet die Kontur; FlatLafs eigener waere eine
        // zweite darueber.
        button.setBorderPainted(false);
        button.setMnemonic(mnemonic);
        button.getAccessibleContext().setAccessibleDescription(beschreibung);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        if (aktion.glyphe != null) {
            button.setIcon(new Symbol(aktion.glyphe));
            button.setIconTextGap(7);
        }
        button.putClientProperty("aktion", aktion);
        button.addChangeListener(e -> applyButtonColors(button));
        return button;
    }

    /**
     * Der Schaltflaechen-Zustand laeuft ueber einen {@code ChangeListener}:
     * derselbe feuert bei {@code enabled}, {@code rollover} und {@code pressed}.
     * Damit entfaellt das Ueberschreiben von {@code setEnabled} und damit der
     * Nebenwirkungs-Override aus der alten Fassung.
     */
    private void applyButtonColors(JButton button) {
        Object property = button.getClientProperty("aktion");
        if (!(property instanceof Aktion aktion)) {
            return;
        }
        Theme theme = aktuellesTheme;
        if (aktion == Aktion.LINK) {
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setBorderPainted(false);
            // Der Link hat keinen eigenen Grund, er liegt auf dem Fenster:
            // im Hellen der Akzent, im Dunkeln Weiss.
            setzeRing(button, theme.isDunkel() ? theme.aufAkzent : theme.akzent);
            button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            if (!button.isEnabled()) {
                button.setForeground(theme.gedaempft);
            } else {
                button.setForeground(button.getModel().isRollover() ? theme.akzent : theme.gedaempft);
            }
            return;
        }
        Farben f = farben(theme, aktion);
        button.setFont(new Font(Font.SANS_SERIF,
                aktion == Aktion.FORMATIEREN ? Font.BOLD : Font.PLAIN, 12));
        boolean aktiv = button.getModel().isRollover() || button.getModel().isPressed();
        button.setOpaque(true);
        button.setBorderPainted(true);
        button.setContentAreaFilled(f.stil() != Stil.UMRANDET);

        if (!button.isEnabled()) {
            // Stufenfarbe und Linie entschaerft: gesperrt heisst "noch nicht",
            // nicht "gehoert zu einer anderen Funktion". Eine volle
            // Akzentlinie wuerde den Knopf aktiv aussehen lassen.
            button.setBorder(knopfRand(theme, theme.gesperrt(f.rand())));
            button.setBackground(theme.gesperrt(flaeche(f, false)));
            button.setForeground(theme.aufDeaktiviert);
            setzeRing(button, theme.gedaempft);
            return;
        }
        button.setBorder(knopfRand(theme, f.rand()));
        button.setBackground(flaeche(f, aktiv));
        button.setForeground(f.text());
        // Der Ring muss sich vom eigenen Grund abheben, sonst ist er da und
        // trotzdem nicht zu sehen: auf dem gefuellten Akzentknopf in der
        // Beschriftungsfarbe, im Dunkeln helles Weiss, im Hellen die
        // Akzentfarbe. FlatLafs Standardring laege auf dem Akzentknopf bei
        // 1,1:1 und waere damit blind.
        setzeRing(button, f.stil() == Stil.VOLL || theme.isDunkel()
                ? theme.aufAkzent : theme.akzent);
    }

    private void setzeRing(JButton button, Color farbe) {
        if (button instanceof AktionsButton aktion) {
            aktion.setzeRing(farbe);
        }
    }

    private static Color flaeche(Farben f, boolean aktiv) {
        return aktiv ? f.hover() : f.flaeche();
    }

    /**
     * Rahmen, erhabene Fase und Innenabstand in einem Border. Der 1-px-Rahmen
     * traegt den Kontrast zum Fenster, die Fase macht aus der flachen Flaeche
     * eine erhabene, und der Leerraum haelt den Text vom Rand weg.
     */
    private static Border knopfRand(Theme theme, Color rand) {
        return BorderFactory.createCompoundBorder(
                new RundeLinie(rand, 9),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createBevelBorder(BevelBorder.RAISED,
                                theme.rahmen, theme.hintergrund),
                        BorderFactory.createEmptyBorder(6, 14, 6, 14)));
    }

    /** Fuellung, Hover-Fuellung, Linie, Beschriftung und Stufe. */
    private record Farben(Color flaeche, Color hover, Color rand, Color text, Stil stil) { }

    private static Farben farben(Theme theme, Aktion aktion) {
        return switch (aktion) {
            case FORMATIEREN -> new Farben(theme.akzent, theme.akzentHover,
                    theme.rahmen, theme.aufAkzent, Stil.VOLL);
            // Ohne Fuellung: die Linie in voller Akzentfarbe uebernimmt die
            // Abgrenzung, und es bleibt ein zweiter, ruhiger Akzent.
            case LESEN -> new Farben(theme.hintergrund, theme.hintergrund,
                    theme.akzentText, theme.akzentText, Stil.UMRANDET);
            case SCHREIBEN -> new Farben(theme.tonal, theme.tonalHover,
                    theme.rahmen, theme.akzentText, Stil.GETOENT);
            case LINK -> new Farben(theme.gedaempft, theme.akzent, theme.gedaempft,
                    theme.gedaempft, Stil.VOLL);
        };
    }

    /**
     * Die drei Aktionssymbole, mit Java2D gezeichnet statt als Bilddatei:
     * sie nehmen die Textfarbe des Knopfes an und passen damit zu jeder
     * Stufe, zu jedem Theme und zum gesperrten Zustand. Eine Icon-Bibliothek
     * waere fuer drei Strichzeichnungen heavier als der Code, der sie malt.
     */
    private static final class Symbol implements javax.swing.Icon {

        private static final int GROESSE = 15;

        private final Glyphe glyphe;

        Symbol(Glyphe glyphe) {
            this.glyphe = glyphe;
        }

        @Override
        public int getIconWidth() {
            return GROESSE;
        }

        @Override
        public int getIconHeight() {
            return GROESSE;
        }

        @Override
        public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.getForeground());
            g2.translate(x, y);
            g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (glyphe) {
                case KLEMMBRETT -> klemmeBrett(g2);
                case ZAUBERSTAB -> zauberstab(g2);
                case PFEIL -> pfeil(g2);
                case MOND -> mond(g2);
                case SONNE -> sonne(g2);
            }
            g2.dispose();
        }

        /** Klemmbrett: der Inhalt kommt von aussen in die App. */
        private static void klemmeBrett(Graphics2D g2) {
            g2.draw(new java.awt.geom.RoundRectangle2D.Float(2.5f, 3.5f, 10f, 10f, 3f, 3f));
            g2.draw(new java.awt.geom.RoundRectangle2D.Float(5.5f, 1.5f, 4f, 3f, 1.5f, 1.5f));
            g2.draw(new java.awt.geom.Line2D.Float(5f, 8f, 10f, 8f));
            g2.draw(new java.awt.geom.Line2D.Float(5f, 10.5f, 8.5f, 10.5f));
        }

        /** Zauberstab: der Text wird ohne Rueckfrage sortiert. */
        private static void zauberstab(Graphics2D g2) {
            g2.draw(new java.awt.geom.Line2D.Float(2.5f, 12.5f, 9.5f, 5.5f));
            g2.draw(new java.awt.geom.Line2D.Float(1.5f, 10f, 4f, 12.5f));
            g2.draw(new java.awt.geom.Line2D.Float(10f, 1f, 12.5f, 3.5f));
            g2.draw(new java.awt.geom.Line2D.Float(12.5f, 1f, 10f, 3.5f));
            g2.draw(new java.awt.geom.Line2D.Float(11.2f, 1.2f, 11.2f, 3.2f));
            g2.draw(new java.awt.geom.Line2D.Float(10.2f, 2.2f, 12.2f, 2.2f));
        }

        /** Pfeil nach unten in die Ablage: der Text verlaesst die App. */
        /** Sichel statt Vollmond: der ausgeschnittene Kreis wird als
         *  Form subtrahiert, damit der Hintergrund nicht durchscheint. */
        private static void mond(Graphics2D g2) {
            Area kugel = new Area(new Ellipse2D.Float(2, 2, 11, 11));
            kugel.subtract(new Area(new Ellipse2D.Float(6.5f, 0.5f, 11, 11)));
            g2.fill(kugel);
        }

        private static void sonne(Graphics2D g2) {
            Ellipse2D kugel = new Ellipse2D.Float(3.5f, 3.5f, 8, 8);
            g2.fill(kugel);
            g2.translate(7.5f, 7.5f);
            for (int strich = 0; strich < 8; strich++) {
                g2.rotate(Math.PI / 4);
                g2.draw(new Line2D.Float(0, -7, 0, -5.5f));
            }
        }

        private static void pfeil(Graphics2D g2) {
            g2.draw(new java.awt.geom.Line2D.Float(7.5f, 1.5f, 7.5f, 9.5f));
            g2.draw(new java.awt.geom.Line2D.Float(4f, 6.5f, 7.5f, 10f));
            g2.draw(new java.awt.geom.Line2D.Float(11f, 6.5f, 7.5f, 10f));
            g2.draw(new java.awt.geom.Line2D.Float(3f, 13f, 12f, 13f));
        }
    }

    private static String version() {
        String datum = manifestEintrag("Implementation-Date");
        if (datum == null) {
            // Ohne gepacktes Jar (IDE, Tests) gibt es kein Build-Datum. Dann
            // heute - ein leeres oder erfundenes Datum waere schlechter.
            datum = LocalDate.now().format(DATUM_FORMAT);
        }
        return "Version " + nummer() + " vom " + datum;
    }

    /**
     * Die Versionsnummer aus dem Manifest, sonst aus der Datei, die Maven aus
     * der pom.xml erzeugt.
     *
     * <p>Frueher stand hier eine Konstante, die von Hand mit der pom.xml
     * mitgepflegt werden musste - bei drei solcher Stellen pflegt man sie
     * zuverlaessig nur zwei. Das Manifest gilt, sobald das Jar gepackt ist;
     * die Datei deckt IDE und Tests ab, wo es kein Manifest gibt.
     */
    private static String nummer() {
        String ausManifest = manifestEintrag("Implementation-Version");
        if (ausManifest != null) {
            return ausManifest;
        }
        try (InputStream in = FormatterPanel.class.getResourceAsStream(VERSION_DATATEI)) {
            if (in != null) {
                Properties eigenschaften = new Properties();
                eigenschaften.load(in);
                String wert = eigenschaften.getProperty("version");
                if (wert != null && !wert.isBlank() && !wert.startsWith("${")) {
                    return wert.trim();
                }
            }
        } catch (IOException e) {
            // Praktisch nicht erreichbar: gelesen wird eine Datei im eigenen
            // Jar. Sollte es doch passieren, zeigt die Zeile "unbekannt" -
            // das ist ehrlicher als eine leere Anzeige.
        }
        return "unbekannt";
    }

    /**
     * Liest einen Haupt-Eintrag aus dem Manifest des Codes, nicht aus dem
     * Classpath: {@code getResource("/META-INF/MANIFEST.MF")} liebe das
     * Manifest der zuerst gefundenen Dependency und damit womoeglich deren
     * Version.
     *
     * @return der Wert oder {@code null}, wenn es kein gepacktes Jar ist
     */
    private static String manifestEintrag(String schluessel) {
        try {
            URL quelle = FormatterPanel.class.getProtectionDomain().getCodeSource().getLocation();
            if (!"file".equals(quelle.getProtocol())) {
                return null;
            }
            // Aus einem Verzeichnis (target/classes) gibt es kein Jar, dann
            // greift der Rueckfall.
            try (JarFile jar = new JarFile(new File(quelle.toURI()))) {
                Manifest manifest = jar.getManifest();
                return manifest == null ? null : manifest.getMainAttributes().getValue(schluessel);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Nur zur Laufzeit-Erkennung, nicht Teil der Fachlogik.
     */
    @Override
    public String toString() {
        return "FormatterPanel[busy=" + busy + ", sql=" + sqlArea.getText().length() + " Zeichen]";
    }
}
