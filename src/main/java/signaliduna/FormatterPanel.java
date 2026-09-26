package signaliduna;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.datatransfer.ClipboardOwner;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
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

    private static final String FALLBACK_VERSION = "0.1";

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

    /** Wie lange eine Erfolgsmeldung stehen bleibt, bevor sie verschwindet. */
    private static final int TOAST_MILLIS = 4000;

    /**
     * Verzoegerung der Einfaerbung. Beim Tippen soll nicht jeder Tastendruck
     * sofort die ganze Zeile umzeichnen.
     */
    private static final int HERVORHEBUNG_MILLIS = 120;

    /** Der Art einer Schaltflaeche, nicht ihre Bedeutung. */
    private enum Aktion {
        SEKUENDAER,
        AKZENT,
        LINK
    }

    /**
     * Das gerade aktive Theme. Statisch, weil das LookAndFeel selbst global
     * ist: ein zweites Fenster mit einer anderen Palette ergaebe zwei
     * widersprechende Bedienoberflaechen in derselben JVM.
     */
    private static Theme aktuellesTheme = Theme.DUNKEL;

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
    private static final class Editor extends JTextPane {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        /** Hoehe in Zeilen, Breite in Zeichen - wie bei {@link JTextArea}. */
        void setGroesse(int zeilen, int zeichen) {
            int zeilenhoehe = getFontMetrics(getFont()).getHeight();
            int zeichenbreite = getFontMetrics(getFont()).charWidth('0');
            setPreferredSize(new Dimension(zeichen * zeichenbreite, zeilen * zeilenhoehe));
        }
    }

    private final Editor sqlArea = new Editor();

    private final JLabel statusLabel = new JLabel();
    private final JLabel titleLabel = new JLabel("SQL Formatter");
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
    private final JButton readButton = createButton("Clipboard einlesen", Aktion.SEKUENDAER);
    private final JButton formatButton = createButton("SQL Formatieren", Aktion.AKZENT);
    private final JButton writeButton = createButton("Ins Clipboard schreiben", Aktion.SEKUENDAER);
    private final JButton exitButton = createButton("Beenden", Aktion.LINK);
    private final JButton themeButton = createButton("", Aktion.LINK);
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
        sqlArea.setGroesse(EDITOR_ZEILEN, 84);

        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        versionLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dialectLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dialectWirkung.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dialectCombo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        dialectCombo.setSelectedItem(SqlDialect.STANDARD);
        dialectCombo.setToolTipText("Bestimmt, wie sql-formatter den Text interpretiert.");
        dialectCombo.setPreferredSize(new Dimension(170, 26));
        dialectWirkung.setToolTipText(
                "Fuer den Text im Feld ermittelt: diese Dialekte liefern ein anderes Ergebnis.");

        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(16, 18, 14, 18));

        themeButton.setToolTipText(aktuellesTheme.isDunkel()
                ? "Auf das helle Theme umschalten"
                : "Auf das dunkle Theme umschalten");
        header.add(titleLabel, BorderLayout.WEST);
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

        infoLine.add(statusLabel, BorderLayout.WEST);
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
        // Die Flaeche des Textfeldes ist die einzige, die von der
        // Fensterfarbe abweichen soll - so bekommt der Editor eine Kante.
        editorCard.setBorder(BorderFactory.createLineBorder(theme.rand));

        titleLabel.setForeground(theme.text);
        versionLabel.setForeground(theme.gedaempft);
        dialectLabel.setForeground(theme.gedaempft);
        dialectWirkung.setForeground(theme.gedaempft);
        statusLabel.setForeground(theme.farbeFuer(statusRolle));

        dialectCombo.setBackground(theme.flaeche);
        dialectCombo.setForeground(theme.text);

        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setViewportBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(theme.flaeche);
        sqlArea.setBackground(theme.flaeche);
        sqlArea.setForeground(theme.text);
        sqlArea.setCaretColor(theme.text);

        highlighter = new SqlSyntaxHighlighter(theme);
        faerbeHoch();

        themeButton.setText(theme.isDunkel() ? "☀ Hell" : "☾ Dunkel");
        themeButton.setToolTipText(theme.isDunkel()
                ? "Auf das helle Theme umschalten"
                : "Auf das dunkle Theme umschalten");

        for (JButton button : new JButton[]{readButton, formatButton, writeButton, exitButton, themeButton}) {
            applyButtonColors(button);
        }
    }

    private void schalteThemeUm() {
        setzeTheme(aktuellesTheme.isDunkel() ? Theme.HELL : Theme.DUNKEL);
        anwendeTheme();
        // Der Wechsel des LookAndFeel setzt Rahmen und Schriften der
        // Swing-Komponenten zurueck; die eigenen Farben sind bereits gesetzt,
        // die Standardwerte der Fremdkomponenten muessen aber neu geholt werden.
        Window fenster = SwingUtilities.getWindowAncestor(this);
        if (fenster != null) {
            fenster.pack();
        }
        repaint();
    }

    /**
     * Faellt eine Aenderung des Textfelds auf. Wird waehrend der Einfaerbung
     * nicht aufgerufen - siehe {@link #faerbeHoch()}.
     */
    private void onTextChanged() {
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
        String text = sqlArea.getText();
        if (text == null || text.isBlank()) {
            zeigeDialectHinweis("—", null);
            return;
        }
        if (text.length() > DIALECT_PRUEFUNG_MAX_ZEICHEN) {
            zeigeDialectHinweis("—", "Text zu lang, um die Dialekte zu vergleichen.");
            return;
        }

        int generation = ++dialectGeneration;
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

    private void zeigeDialectHinweis(String text, String tooltip) {
        dialectWirkung.setText(text);
        dialectWirkung.setToolTipText(tooltip != null ? tooltip
                : "Fuer den Text im Feld ermittelt: diese Dialekte liefern ein anderes Ergebnis.");
    }

    private void refresh() {
        boolean valid = !busy && SqlDetector.looksLikeSql(sqlArea.getText());
        formatButton.setEnabled(valid);
        writeButton.setEnabled(valid);
        readButton.setEnabled(!busy);

        if (busy) {
            return;
        }
        if (valid) {
            setStatus("Bereit - Text sieht nach SQL aus.", Theme.Rolle.NEUTRAL);
        } else if (sqlArea.getText().isBlank()) {
            setStatus("Bereit - Bitte SQL aus der Zwischenablage laden.", Theme.Rolle.NEUTRAL);
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

    private JButton createButton(String text, Aktion aktion) {
        JButton button = new JButton(text);
        button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
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
            button.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            if (!button.isEnabled()) {
                button.setForeground(theme.gedaempft);
            } else {
                button.setForeground(button.getModel().isRollover() ? theme.akzent : theme.gedaempft);
            }
            return;
        }
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFont(new Font(Font.SANS_SERIF, aktion == Aktion.AKZENT ? Font.BOLD : Font.PLAIN, 12));
        if (!button.isEnabled()) {
            button.setBackground(theme.deaktiviert);
            button.setForeground(theme.aufDeaktiviert);
            return;
        }
        boolean aktiv = button.getModel().isRollover() || button.getModel().isPressed();
        boolean akzent = aktion == Aktion.AKZENT;
        button.setBackground(aktiv
                ? (akzent ? theme.akzentHover : theme.sekundaerHover)
                : (akzent ? theme.akzent : theme.sekundaer));
        button.setForeground(akzent ? theme.aufAkzent : theme.aufSekundaer);
    }

    private static String version() {
        Package pkg = FormatterPanel.class.getPackage();
        String fromManifest = pkg == null ? null : pkg.getImplementationVersion();
        return "Version " + (fromManifest != null ? fromManifest : FALLBACK_VERSION);
    }

    /**
     * Nur zur Laufzeit-Erkennung, nicht Teil der Fachlogik.
     */
    @Override
    public String toString() {
        return "FormatterPanel[busy=" + busy + ", sql=" + sqlArea.getText().length() + " Zeichen]";
    }
}
