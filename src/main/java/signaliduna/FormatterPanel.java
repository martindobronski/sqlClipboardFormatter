package signaliduna;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.RootPaneContainer;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.Timer;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
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

/**
 * Enthält die gesamte Oberfläche. Bewusst ein {@link JPanel} und kein
 * {@code JFrame}, damit die UI ohne Bildschirm instantiierbar und der
 * Formatter davon testbar bleibt.
 */
public final class FormatterPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String FALLBACK_VERSION = "0.1";

    private static final Color PANEL_BACKGROUND = new Color(245, 245, 245);
    private static final Color TEXT_COLOR = new Color(33, 33, 33);
    private static final Color MUTED_COLOR = new Color(100, 100, 100);
    private static final Color STATUS_NEUTRAL = new Color(100, 100, 100);
    private static final Color STATUS_OK = new Color(0, 128, 0);
    private static final Color STATUS_ERROR = new Color(190, 30, 30);
    private static final Color DISABLED_BACKGROUND = new Color(214, 214, 214);
    private static final Color DISABLED_FOREGROUND = new Color(80, 80, 80);

    private static final int BUTTON_GAP = 10;

    private final JTextArea sqlArea = new JTextArea();
    private final JLabel statusLabel = new JLabel();
    /**
     * Oberhalb dieser Zeichenzahl wird die Dialektpruefung abgebrochen: sie
     * braucht eine Formatierung je Dialekt, das waeren bei 200 Statements rund
     * zwei Sekunden. Beim Tippen waere das unbrauchbar, und die Information ist
     * dann auch nur noch Nebel - die Statuszeile beim Formatieren bleibt auch
     * fuer lange Texte korrekt.
     */
    private static final int DIALECT_PRUEFUNG_MAX_ZEICHEN = 4_000;

    /** Verzoegerung, damit jeder Tastendruck nicht sofort sechs Formatierungen ausloest. */
    private static final int DIALECT_PRUEFUNG_VERZOEGERUNG_MS = 300;

    private final JComboBox<SqlDialect> dialectCombo = new JComboBox<>(SqlDialect.values());
    private final JLabel dialectWirkung = new JLabel();
    private final Timer dialectTimer =
            new Timer(DIALECT_PRUEFUNG_VERZOEGERUNG_MS, e -> pruefeWirksameDialekte());

    /** Zaehlt hoch, damit das Ergebnis eines veralteten Laufs verworfen wird. */
    private int dialectGeneration;
    private final JButton readButton = createStyledButton("Clipboard einlesen", new Color(70, 130, 180));
    private final JButton formatButton = createStyledButton("SQL Formatieren", new Color(46, 139, 87));
    private final JButton writeButton = createStyledButton("Ins Clipboard schreiben", new Color(220, 100, 50));
    private final JButton exitButton = createStyledButton("Beenden", new Color(178, 60, 50));

    private boolean busy;

    public FormatterPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(15, 15, 15, 15));
        setBackground(PANEL_BACKGROUND);

        JLabel titleLabel = new JLabel("SQL Formatter", SwingConstants.CENTER);
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        titleLabel.setForeground(TEXT_COLOR);
        add(titleLabel, BorderLayout.NORTH);

        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sqlArea.setLineWrap(true);
        sqlArea.setWrapStyleWord(true);
        sqlArea.setPreferredSize(new Dimension(760, 420));

        JScrollPane scrollPane = new JScrollPane(sqlArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("SQL Code"));
        add(scrollPane, BorderLayout.CENTER);

        // GridBagLayout statt GridLayout: jede Schaltfläche bekommt ihre
        // natürliche Breite. Bei GridLayout(1, 4) wuerden alle auf 185 px
        // gestreckt und "Ins Clipboard schreiben" (192 px) abgeschnitten.
        JPanel buttonPanel = new JPanel(new GridBagLayout());
        buttonPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.insets = new Insets(0, 0, 0, BUTTON_GAP);
        for (JButton button : new JButton[]{readButton, formatButton, writeButton, exitButton}) {
            buttonPanel.add(button, gbc);
            gbc.gridx++;
        }

        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        statusLabel.setForeground(STATUS_NEUTRAL);

        JLabel versionLabel = new JLabel(version());
        versionLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        versionLabel.setForeground(MUTED_COLOR);

        JPanel infoLine = new JPanel(new BorderLayout());
        infoLine.setOpaque(false);
        infoLine.setBorder(new EmptyBorder(5, 0, 0, 0));
        infoLine.add(statusLabel, BorderLayout.WEST);
        infoLine.add(versionLabel, BorderLayout.EAST);

        // Der Dialekt ist keine Kosmetik: sql-formatter zerschlitzt z. B.
        // $$ ... $$ in allen Dialekten ausser PostgreSQL.
        JLabel dialectLabel = new JLabel("Dialect:");
        dialectLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dialectLabel.setForeground(MUTED_COLOR);

        dialectCombo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        dialectCombo.setSelectedItem(SqlDialect.STANDARD);
        dialectCombo.setToolTipText("Bestimmt, wie sql-formatter den Text interpretiert.");
        dialectCombo.setPreferredSize(new Dimension(170, 24));

        JPanel dialectRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        dialectRow.setOpaque(false);
        dialectWirkung.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        dialectWirkung.setForeground(MUTED_COLOR);
        dialectWirkung.setText("\u2014");
        dialectWirkung.setToolTipText(
                "Fuer den Text im Feld ermittelt: diese Dialekte liefern ein anderes Ergebnis.");

        dialectRow.add(dialectLabel);
        dialectRow.add(dialectCombo);
        dialectRow.add(dialectWirkung);

        JPanel footer = new JPanel(new BorderLayout(10, 5));
        footer.setOpaque(false);
        footer.add(dialectRow, BorderLayout.NORTH);
        footer.add(buttonPanel, BorderLayout.CENTER);
        footer.add(infoLine, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        readButton.addActionListener(e -> readFromClipboard());
        formatButton.addActionListener(e -> formatSqlText());
        writeButton.addActionListener(e -> writeToClipboard());
        exitButton.addActionListener(e -> exitApplication());

        // Ohne diesen Listener blieb die Freischaltung nach dem Einlesen stehen,
        // sobald der Text von Hand geändert wurde.
        sqlArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onTextChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onTextChanged();
            }
        });

        refresh();
    }

    private void onTextChanged() {
        refresh();
        dialectTimer.restart();
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
            zeigeDialectHinweis("\u2014", null);
            return;
        }
        if (text.length() > DIALECT_PRUEFUNG_MAX_ZEICHEN) {
            zeigeDialectHinweis("\u2014", "Text zu lang, um die Dialekte zu vergleichen.");
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
                    zeigeDialectHinweis("\u2014", null);
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
            setStatus("Bereit - Text sieht nach SQL aus.", STATUS_NEUTRAL);
        } else if (sqlArea.getText().isBlank()) {
            setStatus("Bereit - Bitte SQL aus der Zwischenablage laden.", STATUS_NEUTRAL);
        } else {
            setStatus("⚠ Der Text sieht nicht nach SQL aus. Formatieren und Schreiben sind blockiert.", STATUS_ERROR);
        }
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void setBusy(boolean value) {
        busy = value;
        refresh();
    }

    private void readFromClipboard() {
        setBusy(true);
        setStatus("Lese Zwischenablage ...", STATUS_NEUTRAL);

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
                    setStatus("❌ Lesen der Zwischenablage abgebrochen.", STATUS_ERROR);
                    return;
                } catch (ExecutionException ex) {
                    setStatus("❌ Fehler beim Lesen der Zwischenablage: " + ex.getCause(), STATUS_ERROR);
                    return;
                }

                switch (result.status()) {
                    case OK -> {
                        sqlArea.setText(result.text());
                        sqlArea.setCaretPosition(0);
                        if (SqlDetector.looksLikeSql(result.text())) {
                            setStatus("✓ Text geladen. Formatieren und Schreiben sind freigeschaltet.", STATUS_OK);
                        } else {
                            setStatus("⚠ Text geladen, sieht aber nicht nach SQL aus. Aktionen blockiert.", STATUS_ERROR);
                        }
                    }
                    case NO_TEXT, EMPTY -> setStatus("⚠ " + result.detail(), STATUS_ERROR);
                    case UNAVAILABLE -> setStatus("❌ " + result.detail(), STATUS_ERROR);
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
        setStatus("Formatiere ...", STATUS_NEUTRAL);

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
                    setStatus("❌ Formatierung abgebrochen.", STATUS_ERROR);
                    return;
                } catch (ExecutionException ex) {
                    setStatus("❌ Fehler beim Formatieren: " + ex.getCause().getMessage(), STATUS_ERROR);
                    return;
                }

                sqlArea.setText(result.sql());
                sqlArea.setCaretPosition(Math.min(caret, result.sql().length()));

                // Nur die Erfolgsmeldung setzen, wenn der Text weiterhin als SQL
                // erkannt wird - sonst gilt die Warnung aus refresh().
                if (SqlDetector.looksLikeSql(result.sql())) {
                    setStatus(successMessage(result), result.bestPractice() ? STATUS_OK : STATUS_NEUTRAL);
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
            setStatus("⚠ Das Textfeld ist leer. Nichts zu kopieren.", STATUS_ERROR);
            return;
        }

        setBusy(true);
        setStatus("Schreibe in die Zwischenablage ...", STATUS_NEUTRAL);

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
                    setStatus("❌ Schreiben abgebrochen.", STATUS_ERROR);
                    return;
                } catch (ExecutionException ex) {
                    setStatus("❌ Fehler beim Schreiben: " + ex.getCause().getMessage(), STATUS_ERROR);
                    return;
                }
                setStatus("✓ SQL erfolgreich in die Zwischenablage kopiert.", STATUS_OK);
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
     * Der Button-Zustand laeuft ueber einen {@code ChangeListener}: derselbe
     * feuert bei {@code enabled}, {@code rollover} und {@code pressed}. Damit
     * entfaellt das Ueberschreiben von {@code setEnabled} und damit der
     * Nebenwirkungs-Override aus der alten Fassung.
     */
    private JButton createStyledButton(String text, Color baseColor) {
        JButton button = new JButton(text);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(true);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.putClientProperty("baseColor", baseColor);
        applyButtonColors(button);
        button.addChangeListener(e -> applyButtonColors(button));
        return button;
    }

    private static void applyButtonColors(JButton button) {
        Object property = button.getClientProperty("baseColor");
        if (!(property instanceof Color base)) {
            return;
        }
        if (!button.isEnabled()) {
            button.setBackground(DISABLED_BACKGROUND);
            button.setForeground(DISABLED_FOREGROUND);
        } else {
            button.setBackground(button.getModel().isRollover() ? base.brighter() : base);
            button.setForeground(Color.WHITE);
        }
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
