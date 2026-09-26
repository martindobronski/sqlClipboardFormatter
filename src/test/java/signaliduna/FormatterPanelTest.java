package signaliduna;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.JTextComponent;
import javax.swing.JTextPane;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.awt.Color;
import java.awt.BorderLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testet die Verdrahtung der echten UI, insbesondere das Verhalten, das vorher
 * fehlte: nach dem Einlesen blieben die Aktionen freigeschaltet, auch wenn der
 * Text danach von Hand geaendert wurde.
 *
 * <p>Laeuft headless - {@code FormatterPanel} ist ein {@code JPanel} und braucht
 * keinen Bildschirm.
 */
class FormatterPanelTest {

    private FormatterPanel panel;
    private JTextComponent sqlArea;
    private JButton readButton;
    private JButton formatButton;
    private JButton writeButton;
    private JButton exitButton;
    @SuppressWarnings("unchecked")
    private JComboBox<SqlDialect> dialectCombo;
    private JLabel statusLabel;
    private JLabel dialectWirkung;

    @BeforeEach
    void setUp() throws Exception {
        // Das aktive Theme ist statisch; ohne Zuruecksetzen haengt jeder Test
        // vom zuletzt gelaufenen Theme-Test ab.
        FormatterPanel.setzeTheme(Theme.DUNKEL);
        panel = new FormatterPanel();
        sqlArea = field("sqlArea");
        readButton = field("readButton");
        formatButton = field("formatButton");
        writeButton = field("writeButton");
        exitButton = field("exitButton");
        dialectCombo = field("dialectCombo");
        statusLabel = field("statusLabel");
        dialectWirkung = field("dialectWirkung");
    }

    @Nested
    @DisplayName("Freischaltung haengt am Text")
    class Freischaltung {

        @Test
        void startzustand_ist_gesperrt() {
            assertFalse(formatButton.isEnabled(), "Formatieren muss starten gesperrt");
            assertFalse(writeButton.isEnabled(), "Schreiben muss starten gesperrt");
            assertTrue(readButton.isEnabled(), "Einlesen muss starten moeglich sein");
        }

        @Test
        void gueltiges_sql_schaltet_frei() {
            sqlArea.setText("select * from users where id = 1");
            assertTrue(formatButton.isEnabled());
            assertTrue(writeButton.isEnabled());
        }

        @Test
        @DisplayName("Handeingabe von Nicht-SQL sperrt wieder - der zentrale Fix")
        void handeingabe_sperrt_wieder() {
            sqlArea.setText("select * from users");
            assertTrue(formatButton.isEnabled() && writeButton.isEnabled());

            sqlArea.setText("Einkaufsliste");

            assertFalse(formatButton.isEnabled(), "Formatieren muss nach Handeingabe sperren");
            assertFalse(writeButton.isEnabled(), "Schreiben muss nach Handeingabe sperren");
        }

        @Test
        void zurueck_zu_sql_schaltet_wieder_frei() {
            sqlArea.setText("Einkaufsliste");
            assertFalse(formatButton.isEnabled());

            sqlArea.setText("TRUNCATE TABLE t");

            assertTrue(formatButton.isEnabled(), "TRUNCATE wurde frueher fälschlich abgelehnt");
        }

        @Test
        void nur_kommentar_sperrt() {
            sqlArea.setText("-- nur ein Kommentar");
            assertFalse(formatButton.isEnabled());
        }

        @Test
        void leerer_text_sperrt_und_haelt_einlesen_offen() {
            sqlArea.setText("   ");

            assertFalse(formatButton.isEnabled());
            assertFalse(writeButton.isEnabled());
            assertTrue(readButton.isEnabled(), "Einlesen muss auch bei leerem Text moeglich bleiben");
        }

        @Test
        void abgeschnittenes_sql_bleibt_nutzbar_und_stuerzt_nicht_ab() {
            sqlArea.setText("select 'unbalanced  literal");
            assertTrue(formatButton.isEnabled());
            assertTrue(SqlFormatService.format(sqlArea.getText()).contains("SELECT 'unbalanced  literal"));
        }
    }

    @Nested
    @DisplayName("Statusmeldungen")
    class Statusmeldungen {

        @Test
        void warnung_bei_nicht_sql() {
            sqlArea.setText("Einkaufsliste");
            assertTrue(statusLabel.getText().contains("blockiert"),
                    "Erwartet Blockier-Hinweis, war: " + statusLabel.getText());
        }

        @Test
        void neutrale_meldung_bei_gueltigem_sql() {
            sqlArea.setText("select 1");
            assertEquals("Bereit - Text sieht nach SQL aus.", statusLabel.getText());
        }

        @Test
        void einles_meldung_bei_leerem_feld() {
            sqlArea.setText("");
            assertEquals("Bereit - Bitte SQL aus der Zwischenablage laden.", statusLabel.getText());
        }
    }

    @Nested
    @DisplayName("Formatieren ueber den echten Button")
    class FormatierenDurchClick {

        @Test
        @DisplayName("der Library-Text landet im Feld")
        void bibliothekstext_landet_im_feld() throws Exception {
            sqlArea.setText("select id,name from users where active = 1");
            assertTrue(formatButton.isEnabled(), "Vorbedingung: muss freigeschaltet sein");

            formatButton.doClick();
            warteAufErgebnis();

            assertEquals("""
                    SELECT
                        id,
                        name
                    FROM
                        users
                    WHERE
                        active = 1""", sqlArea.getText());
            assertTrue(statusLabel.getText().contains("Best Practice"),
                    "Status: " + statusLabel.getText());
        }

        @Test
        @DisplayName("ein Rueckfall wird als solcher gemeldet, nicht verschwiegen")
        void rueckfall_wird_gemeldet() throws Exception {
            sqlArea.setText("select * from t where d = date'2020-01-01'");

            formatButton.doClick();
            warteAufErgebnis();

            assertTrue(sqlArea.getText().contains("date'2020-01-01'"),
                    "Inhalt zerstoert: " + sqlArea.getText());
            assertTrue(statusLabel.getText().contains("Nur normalisiert"),
                    "Status: " + statusLabel.getText());
        }

        @Test
        @DisplayName("der Cursor springt nicht ans Ende, wenn der Text kuerzer wurde")
        void cursor_bleibt_near_an_seiner_stelle() throws Exception {
            sqlArea.setText("select id,name from users where active = 1");
            sqlArea.setCaretPosition(10);

            formatButton.doClick();
            warteAufErgebnis();

            assertEquals(10, sqlArea.getCaretPosition(),
                    "Caret ist auf " + sqlArea.getCaretPosition());
        }

        @Test
        @DisplayName("Dollar-Quoting schaltet den Dialekt und meldet es")
        void dollar_quoting_meldet_dialektwechsel() throws Exception {
            sqlArea.setText("create function f() returns int as $$ select 1 $$ language sql;");

            formatButton.doClick();
            warteAufErgebnis();

            assertTrue(sqlArea.getText().contains("$$ select 1 $$"),
                    "Koerper veraendert: " + sqlArea.getText());
            assertTrue(statusLabel.getText().contains("PostgreSQL"),
                    "Status: " + statusLabel.getText());
        }

        @Test
        @DisplayName("ein wirkungsloser Dialekt wird dem Nutzer auch sagen")
        void wirkungsloser_dialekt_wird_gemeldet() throws Exception {
            // Der Ausgangsfall der Anfrage: beim Umschalten passiert scheinbar
            // nichts. MySQL liefert fuer diese Abfrage exakt dasselbe wie
            // Standard SQL - das wird jetzt auch so gesagt.
            sqlArea.setText("select a, b from users where active = 1");
            dialectCombo.setSelectedItem(SqlDialect.MYSQL);

            formatButton.doClick();
            warteAufErgebnis();

            String status = statusLabel.getText();
            assertTrue(status.contains("aendert diesen Text nicht"),
                    "Wirkungslosigkeit wird verschwiegen: " + status);
            assertTrue(status.contains("Best Practice"), "trotzdem formatiert: " + status);
        }

        @Test
        @DisplayName("ein wirkender Dialekt wird nicht als wirkungslos gemeldet")
        void wirkender_dialekt_wird_nicht_gemeldet() throws Exception {
            // PL/SQL schreibt Bezeichner gross, das ist eine echte Wirkung.
            sqlArea.setText("select a, b from users where active = 1");
            dialectCombo.setSelectedItem(SqlDialect.PLSQL);

            formatButton.doClick();
            warteAufErgebnis();

            String status = statusLabel.getText();
            assertFalse(status.contains("aendert diesen Text nicht"),
                    "falscher Hinweis trotz Wirkung: " + status);
            assertTrue(sqlArea.getText().contains("A,"),
                    "Bezeichner nicht grossgeschrieben: " + sqlArea.getText());
        }

        @Test
        @DisplayName("zerstoerte Operatoren fuehlen sich nicht wie Best Practice an")
        void zerstoerte_operatoren_wird_gemeldet() throws Exception {
            sqlArea.setText("select a || 'x' from t");
            dialectCombo.setSelectedItem(SqlDialect.POSTGRESQL);

            formatButton.doClick();
            warteAufErgebnis();

            assertTrue(statusLabel.getText().contains("Nur normalisiert"),
                    "Status: " + statusLabel.getText());
            assertTrue(sqlArea.getText().contains("a || 'x'"),
                    "Operator zerstoert: " + sqlArea.getText());
        }

        @Test
        @DisplayName("die Formatierung laeuft wirklich im Hintergrund")
        void laeuft_auf_anderem_thread() throws Exception {
            // Der EDT darf waehrend des Formatierens nicht blockiert sein. Wir
            // haetten das schon gemerkt, wenn SwingWorker durch einen
            // blockierenden Aufruf ersetzt worden waere.
            sqlArea.setText("select a, b, c from t where a = 1 and b = 2 or c = 3");

            formatButton.doClick();

            // Sofort und ohne zu warten muss der EDT noch bedienbar sein.
            String sofort = leseAufEdt(statusLabel::getText);
            assertFalse(sofort.contains("Best Practice"),
                    "Ergebnis war zu frueh da - der EDT lief synchron mit: " + sofort);

            warteAufErgebnis();
            assertTrue(statusLabel.getText().contains("Best Practice"), "Status: " + statusLabel.getText());
        }
    }

    @Nested
    @DisplayName("Anzeige der wirksamen Dialekte")
    class WirkungsAnzeige {

        /** Die Pruefung laeuft entprellt im Hintergrund, also warten. */
        private void warteAufDialectAnzeige(String erwartet) throws Exception {
            for (int i = 0; i < 400; i++) {
                if (erwartet.equals(dialectWirkung.getText())) {
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("Anzeige blieb: '" + dialectWirkung.getText()
                    + "', erwartet: '" + erwartet + "'");
        }

        @Test
        @DisplayName("bei gewöhnlichem SQL genuegt Standard SQL")
        void standard_genuegt() throws Exception {
            sqlArea.setText("select 1");
            warteAufDialectAnzeige("Standard SQL genügt");
        }

        @Test
        @DisplayName("TOP wird als T-SQL gemeldet")
        void top_wird_gemeldet() throws Exception {
            sqlArea.setText("select top 10 id from t");
            warteAufDialectAnzeige("wirksam: T-SQL");
        }

        @Test
        @DisplayName("leeres Feld zeigt nichts an")
        void leeres_feld() throws Exception {
            sqlArea.setText("select 1");
            warteAufDialectAnzeige("Standard SQL genügt");

            sqlArea.setText("");
            warteAufDialectAnzeige("—");
        }

        @Test
        @DisplayName("sehr lange Texte werden nicht durchgerechnet")
        void lange_texte_werden_ausgelassen() throws Exception {
            // Sonst wuerde beim Tippen jeder Tastendruck eine halbe Sekunde
            // blockieren - die Grenze gehoert also getestet.
            sqlArea.setText("select 1\n".repeat(3_000));
            Thread.sleep(1_200);
            assertEquals("—", dialectWirkung.getText());
        }

        @Test
        @DisplayName("die Anzeige blockiert die Bedienung nicht")
        void anzeige_ist_asynchron() throws Exception {
            sqlArea.setText("select top 10 id from t");
            // Sofort nach dem Setzen muss der EDT noch antworten.
            assertEquals("—", dialectWirkung.getText(),
                    "Die Anzeige lief synchron mit.");
            warteAufDialectAnzeige("wirksam: T-SQL");
        }
    }

    @Nested
    @DisplayName("Dialect-Auswahl")
    class Dialect {

        @Test
        void alle_dialekte_wah_bar() {
            assertEquals(SqlDialect.values().length, dialectCombo.getItemCount());
            for (SqlDialect dialect : SqlDialect.values()) {
                assertTrue(dialectCombo.getItemAt(dialect.ordinal()) == dialect,
                        "fehlt im Modell: " + dialect);
            }
        }

        @Test
        @DisplayName("Voreinstellung ist Standard SQL")
        void standard_ist_voreingestellt() {
            assertEquals(SqlDialect.STANDARD, dialectCombo.getSelectedItem());
        }

        @Test
        void auswahl_liefert_labels_nicht_enum_namen() {
            for (int i = 0; i < dialectCombo.getItemCount(); i++) {
                String rendered = dialectCombo.getItemAt(i).toString();
                assertFalse(rendered.isBlank(), "leeres Label");
                assertFalse(rendered.equals(dialectCombo.getItemAt(i).name()),
                        "Enum-Name statt Label: " + rendered);
                assertFalse(rendered.contains("_"), "Unterstrich im Label: " + rendered);
            }
        }

        @Test
        @DisplayName("eine leere Auswahl darf die Formatierung nicht sprengen")
        void leere_auswahl_ist_vertragsgemaess() {
            dialectCombo.setSelectedItem(null);
            assertNull(dialectCombo.getSelectedItem(), "Voraussetzung fuer diesen Vertrag");

            // Der Formatter bekommt den Wert ungeprueft durchgereicht und muss
            // null auf Standard abbilden - sonst waere die App hier angreifbar.
            assertEquals(
                    SqlPrettyFormatter.format("select 1", SqlDialect.STANDARD).sql(),
                    SqlPrettyFormatter.format("select 1", (SqlDialect) dialectCombo.getSelectedItem()).sql(),
                    "null-Dialekt muss wie Standard behandelt werden");
        }
    }

    @Nested
    @DisplayName("Statusmeldung nennt den gewaehlten Pfad")
    class Status {

        @Test
        void best_practice_wird_bestaetigt() {
            String text = FormatterPanel.successMessage(
                    new SqlPrettyFormatter.Result("SELECT 1", true, null));
            assertTrue(text.contains("Best Practice"), text);
            assertFalse(text.contains("\u26a0"), "keine Warnung: " + text);
        }

        @Test
        void rueckfall_wird_als_warnung_ausgewiesen() {
            String text = FormatterPanel.successMessage(
                    new SqlPrettyFormatter.Result("SELECT 1", false, "Grund"));
            assertTrue(text.contains("Nur normalisiert"), text);
            assertTrue(text.contains("Grund"), "der Grund muss sichtbar sein: " + text);
        }

        @Test
        void dialektwechsel_wird_erklaert() {
            String text = FormatterPanel.successMessage(
                    new SqlPrettyFormatter.Result("SELECT 1", true, "PostgreSQL gewaehlt"));
            assertTrue(text.contains("Best Practice") && text.contains("PostgreSQL"), text);
        }
    }

    @Nested
    @DisplayName("Beenden-Button")
    class BeendenButton {

        @Test
        @DisplayName("ist ein Textlink ohne eigene Flaeche, kein roter Kasten")
        void ist_als_textlink_gesetzt() {
            assertEquals("Beenden", exitButton.getText());
            // Vorher: rote, gefuellte Flaeche - dieselbe visuelle Gewichtung
            // wie "Ins Clipboard schreiben", obwohl es nichts mit dem
            // SQL-Text zu tun hat.
            assertFalse(exitButton.isContentAreaFilled(), "Beenden darf keine Flaeche malen");
            assertEquals(new Color(0x9AA4B0), exitButton.getForeground());
        }

        @Test
        @DisplayName("steht abgesetzt rechts, nicht in der Aktionsgruppe")
        void steht_abgesetzt_rechts_der_aktionsgruppe() {
            // Die drei Aktionen teilen sich eine eigene Flaeche, "Beenden"
            // sitzt rechts in einer zweiten. Sonst laesst es sich nicht
            // optisch von ihnen trennen.
            assertNotSame(exitButton.getParent(), readButton.getParent(),
                    "Beenden darf nicht in derselben Gruppe liegen wie die Aktionen");

            // Geometrie statt Struktur: mass, ob es wirklich rechts klebt.
            JPanel reihe = (JPanel) exitButton.getParent();
            assertInstanceOf(BorderLayout.class, reihe.getLayout());
            reihe.setSize(800, 40);
            reihe.doLayout();
            assertTrue(exitButton.getX() + exitButton.getWidth() >= 780,
                    "Beenden muss an der rechten Kante stehen, lag bei x=" + exitButton.getX());
        }

        @Test
        @DisplayName("bleibt immer aktiv - auch bei Nicht-SQL und waehrend eines Vorgangs")
        void bleibt_immer_aktiv() {
            assertTrue(exitButton.isEnabled(), "darf beim Start nicht gesperrt sein");

            sqlArea.setText("Einkaufsliste");
            assertTrue(exitButton.isEnabled(), "darf bei Nicht-SQL nicht gesperrt werden");

            sqlArea.setText("select 1");
            assertTrue(exitButton.isEnabled(), "darf bei gueltigem SQL nicht gesperrt werden");
        }

        @Test
        @DisplayName("klick beendet ohne Absturz und ohne die JVM zu beenden")
        void klick_beendet_ohne_absturz() {
            // Ohne umgebendes Fenster greift der Fallback: nur ausblenden.
            // Ein System.exit(0) wuerde die Test-JVM sofort beenden.
            assertNull(SwingUtilities.getWindowAncestor(panel),
                    "Voraussetzung: Panel haengt in keinem Fenster");

            exitButton.doClick();

            assertFalse(panel.isVisible(), "Panel muss nach dem Klick ausgeblendet sein");
        }

        @Test
        @DisplayName("harter Exit nur, wenn das Panel das ganze Fenster besitzt")
        void harter_exit_nur_bei_alleinigem_inhalt() {
            assertTrue(FormatterPanel.isSoleContent(panel, panel),
                    "setContentPane(panel): das Panel IST der ContentPane");

            JPanel huelle = new JPanel();
            huelle.add(panel);
            assertTrue(FormatterPanel.isSoleContent(huelle, panel),
                    "einziges Kind der Huelle -> hartes Beenden erlaubt");

            JPanel fremdeHuelle = new JPanel();
            fremdeHuelle.add(new JLabel("fremde UI"));
            assertFalse(FormatterPanel.isSoleContent(fremdeHuelle, panel),
                    "fremde Komponenten -> Prozess der Einbettenden lebt weiter");

            JPanel zweitesKind = new JPanel();
            zweitesKind.add(panel);
            zweitesKind.add(new JLabel("zweites Kind"));
            assertFalse(FormatterPanel.isSoleContent(zweitesKind, panel),
                    "mehrere Kinder -> kein hartes Beenden");

            assertFalse(FormatterPanel.isSoleContent(new JPanel(), panel),
                    "leeres Fenster -> kein Beenden");
        }
    }

    @Nested
    @DisplayName("Beschriftungen werden nicht abgeschnitten")
    class Beschriftungen {

        @Test
        void alle_buttons_bekommen_ihre_naturliche_breite() {
            // Bei GridLayout(1, 4) haetten alle Spalten 185 px, waehrend
            // "Ins Clipboard schreiben" 192 px braucht.
            panel.setSize(panel.getPreferredSize());
            layOutRecursively(panel);

            for (JButton button : new JButton[]{readButton, formatButton, writeButton, exitButton}) {
                assertTrue(button.getWidth() >= button.getPreferredSize().width,
                        "'" + button.getText() + "' bekommt " + button.getWidth()
                                + " px, benoetigt aber " + button.getPreferredSize().width + " px");
            }
        }

        private void layOutRecursively(java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                if (child instanceof java.awt.Container nested) {
                    layOutRecursively(nested);
                }
            }
        }
    }

    @Nested
    @DisplayName("Darstellung")
    class Darstellung {

        @Test
        @DisplayName("die Versionszeile nennt die Version und ihr Datum")
        void versionszeile_nennt_version_und_datum() throws Exception {
            JLabel versionLabel = field("versionLabel");
            assertTrue(versionLabel.getText().matches("Version \\S+ vom \\d{2}\\.\\d{2}\\.\\d{4}"),
                    "unerwartete Form: '" + versionLabel.getText() + "'");
        }

        @Test
        @DisplayName("die Versionszeile zeigt genau die Version aus der pom.xml")
        void versionszeile_zeigt_die_pom_version() throws Exception {
            // Aus der pom.xml gelesen statt hier eingetragen: sonst prueft der
            // Test eine Zahl, die beim naechsten Versionswechsel nur noch
            // aussieht als waere sie richtig.
            String erwartet = versionAusPom();
            JLabel versionLabel = field("versionLabel");
            assertTrue(versionLabel.getText().startsWith("Version " + erwartet + " vom "),
                    "erwartet Version aus der pom.xml '" + erwartet
                            + "', angezeigt: '" + versionLabel.getText() + "'");
        }

        private String versionAusPom() throws Exception {
            String pom = java.nio.file.Files.readString(
                    new File("pom.xml").toPath(), StandardCharsets.UTF_8);
            java.util.regex.Matcher treffer =
                    java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(pom);
            assertTrue(treffer.find(), "keine <version> in der pom.xml gefunden");
            return treffer.group(1);
        }

        @Test
        @DisplayName("die Versionszeile sitzt rechts unten")
        void versionszeile_sitzt_rechts_unten() throws Exception {
            JPanel infoLine = field("infoLine");
            JPanel footer = field("footer");
            JLabel versionLabel = field("versionLabel");
            JLabel statusLabel = field("statusLabel");

            // Nicht an Layout-Konstanten pruefen: die Panels nutzen
            // new BorderLayout(0, 10), nicht BorderLayout.EAST. Gemessen wird
            // die Lage im gerenderten Kasten.
            panel.setSize(700, 520);
            panel.doLayout();
            footer.doLayout();
            infoLine.doLayout();

            assertSame(infoLine, versionLabel.getParent(), "die Version steht in der Fusszeile");
            assertEquals(infoLine.getWidth(),
                    versionLabel.getX() + versionLabel.getWidth(), 1,
                    "die Version klebt am rechten Rand");
            assertEquals(0, statusLabel.getX(), 1, "die Statusmeldung steht links");
            assertEquals(footer.getHeight(),
                    infoLine.getY() + infoLine.getHeight(), 1,
                    "die Zeile ist der unterste Fusszeilenteil");
        }

        @Test
        void schriften_sind_logische_fonts() {
            // Vorher fest auf "Segoe UI"/"Consolas" - unter macOS/Linux kein Monospace.
            assertEquals(java.awt.Font.MONOSPACED, sqlArea.getFont().getFamily());
            assertEquals(java.awt.Font.SANS_SERIF, statusLabel.getFont().getFamily());
        }

        @Test
        @DisplayName("gesperrte Buttons haben ausreichenden Kontrast")
        void gesperrte_buttons_haben_kontrast() {
            sqlArea.setText("Einkaufsliste");
            assertFalse(formatButton.isEnabled());

            assertEquals(new Color(0x2A2E34), formatButton.getBackground());
            assertEquals(new Color(0x9AA4B0), formatButton.getForeground());
            // Vorher: weisse Schrift auf LIGHT_GRAY = 1,82:1
            assertTrue(contrast(formatButton.getBackground(), formatButton.getForeground()) > 4.5,
                    "Kontrast muss WCAG AA (4.5:1) erfuellen");
        }

        @Test
        @DisplayName("nur die Hauptaktion traegt die Akzentfarbe, die anderen bleiben grau")
        void nur_die_hauptaktion_ist_akzentiert() {
            sqlArea.setText("select 1");

            assertEquals(new Color(0x1D4ED8), formatButton.getBackground());
            assertEquals(Color.WHITE, formatButton.getForeground());

            // Vorher jede Schaltflaeche in einer eigenen Farbe (Blau/Gruen/
            // Orange/Rot). Grau ist hier Absicht: die Farbe benennt die
            // Hauptaktion, nicht die Funktion.
            assertEquals(new Color(0x31363C), readButton.getBackground());
            assertEquals(new Color(0x31363C), writeButton.getBackground());
            assertEquals(readButton.getBackground(), writeButton.getBackground(),
                    "Einlesen und Schreiben teilen sich dieselbige Sekundaerfarbe");
        }

        @Test
        void alle_drei_buttons_sind_vorhanden() {
            assertEquals("Clipboard einlesen", readButton.getText());
            assertEquals("SQL Formatieren", formatButton.getText());
            assertEquals("Ins Clipboard schreiben", writeButton.getText());
        }
    }

    @Nested
    @DisplayName("Themes")
    class Themes {

        @Test
        @DisplayName("beide Paletten erfuellen WCAG AA fuer jedes Textpaar")
        void beide_paletten_erfuellen_wcag_aa() {
            List<String> maengel = new ArrayList<>();
            for (Theme theme : List.of(Theme.DUNKEL, Theme.HELL)) {
                // Text auf Fensterflaeche
                pruefe(theme, theme.text, theme.hintergrund, "Text", maengel);
                pruefe(theme, theme.gedaempft, theme.hintergrund, "gedaempft", maengel);
                pruefe(theme, theme.erfolg, theme.hintergrund, "Erfolg", maengel);
                pruefe(theme, theme.warnung, theme.hintergrund, "Warnung", maengel);
                pruefe(theme, theme.fehler, theme.hintergrund, "Fehler", maengel);
                // Beschriftung auf Schaltflaeche
                pruefe(theme, theme.aufAkzent, theme.akzent, "Beschriftung Akzent", maengel);
                pruefe(theme, theme.aufSekundaer, theme.sekundaer, "Beschriftung sekundaer", maengel);
                pruefe(theme, theme.aufDeaktiviert, theme.deaktiviert, "Beschriftung gesperrt", maengel);
                // Syntaxfarben auf der Editorflaeche
                pruefe(theme, theme.keyword, theme.flaeche, "Keyword", maengel);
                pruefe(theme, theme.stringFarbe, theme.flaeche, "String", maengel);
                pruefe(theme, theme.zahl, theme.flaeche, "Zahl", maengel);
                pruefe(theme, theme.kommentar, theme.flaeche, "Kommentar", maengel);
                pruefe(theme, theme.bezeichner, theme.flaeche, "Bezeichner", maengel);
                pruefe(theme, theme.operator, theme.flaeche, "Operator", maengel);
            }
            assertTrue(maengel.isEmpty(), () -> "Kontrast unter WCAG AA: " + maengel);
        }

        private void pruefe(Theme theme, Color fg, Color bg, String was, List<String> maengel) {
            double r = contrast(fg, bg);
            if (r <= 4.5) {
                maengel.add(theme.bezeichnung() + "/" + was + "=" + String.format("%.2f", r));
            }
        }

        @Test
        @DisplayName("Umschalter tauscht LookAndFeel und Farben")
        void umschalter_tauscht_lookandfeel_und_farben() throws Exception {
            JButton themeButton = field("themeButton");
            // Fuelltext noetig: ohne SQL ist der Formatier-Button gesperrt und
            // traegt die Farbe fuer deaktiviert statt die Akzentfarbe.
            sqlArea.setText("select 1");
            assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel());
            assertEquals(Theme.DUNKEL.akzent, formatButton.getBackground());

            aufEdt(themeButton::doClick);

            assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel());
            assertEquals(Theme.HELL.akzent, formatButton.getBackground());
            assertEquals(Theme.HELL.hintergrund, panel.getBackground());
        }

        @Test
        @DisplayName("Umschalten ist umkehrbar")
        void umschalten_ist_umkehrbar() throws Exception {
            JButton themeButton = field("themeButton");
            sqlArea.setText("select 1");
            aufEdt(themeButton::doClick);
            aufEdt(themeButton::doClick);
            assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel());
            assertEquals(Theme.DUNKEL.akzent, formatButton.getBackground());
        }

        @Test
        @DisplayName("der Schalter benennt das Ziel, nicht den Zustand")
        void schalter_benennt_das_ziel() throws Exception {
            JButton themeButton = field("themeButton");
            assertTrue(themeButton.getText().contains("Hell"),
                    "im dunklen Theme muss der Schalter zum hellen Theme fuehren");
            assertNotNull(themeButton.getToolTipText());
        }
    }

    @Nested
    @DisplayName("Syntaxhervorhebung")
    class Hervorhebung {

        @Test
        void keywords_werden_eingefaerbt() {
            faerbe("select id from users");
            assertEquals(Theme.DUNKEL.keyword, farbeAn("select"));
            assertEquals(Theme.DUNKEL.keyword, farbeAn("from"));
        }

        @Test
        @DisplayName("ein Keyword in einer Zeichenkette wird nicht eingefaerbt")
        void keyword_in_zeichenkette_bleibt_ungefaerbt() {
            // Der Grund, warum es den ausgelagerten Scanner gibt: eine
            // Regex-Eigenloesung wuerde hier faerben und sofort billig aussehen.
            faerbe("where name = 'bitte select one' and x = 1");
            assertEquals(Theme.DUNKEL.stringFarbe, farbeAn("select", ab("bitte") + 7),
                    "select innerhalb des Literals ist Text, kein Keyword");
            assertEquals(Theme.DUNKEL.keyword, farbeAn("where"));
        }

        @Test
        @DisplayName("ein Keyword in einem Kommentar wird nicht eingefaerbt")
        void keyword_im_kommentar_bleibt_ungefaerbt() {
            faerbe("-- select ist hier nur Text\nselect 1");
            assertEquals(Theme.DUNKEL.kommentar, farbeAn("select"),
                    "das select im Kommentar");
            int nachKommentar = ab("select", ab("\n") + 1);
            assertEquals(Theme.DUNKEL.keyword, farbeAn("select", nachKommentar),
                    "das select hinter dem Kommentar");
        }

        @Test
        @DisplayName("Kommentare sind zusaetzlich kursiv")
        void kommentare_sind_kursiv() {
            faerbe("select 1 -- notiz");
            assertTrue(kursivAn("-- notiz"));
        }

        @Test
        void strings_und_bezeichner_haben_unterschiedliche_farben() {
            faerbe("select \"spalte\" from 'tabelle'");
            assertEquals(Theme.DUNKEL.bezeichner, farbeAn("\"spalte\""));
            assertEquals(Theme.DUNKEL.stringFarbe, farbeAn("'tabelle'"));
        }

        @Test
        void zahlen_haben_eigene_farbe() {
            faerbe("select 1 from t where x = 3.5e2");
            assertEquals(Theme.DUNKEL.zahl, farbeAn("3.5e2"), "Exponent muss mitgefaerbt werden");
        }

        @Test
        @DisplayName("dieselbe Zerlegung wie der Formatter: jedes seiner Keywords wird auch gefaehrt")
        void keywords_stimmen_mit_dem_formatter_ueberein() {
            // Drift-Wache: faellt ein Keyword aus dem Formatter weg, faellt es
            // hier auf, statt still ungefaerbt zu bleiben.
            Set<String> woerter = new HashSet<>();
            Matcher m = Pattern.compile("[a-z]{3,}").matcher(SqlFormatService.KEYWORDS.pattern());
            while (m.find()) {
                woerter.add(m.group());
            }
            assertFalse(woerter.isEmpty(), "Keyword-Muster des Formatierers ausgewertet");
            List<String> fehlen = woerter.stream()
                    .filter(w -> !SqlSyntaxHighlighter.keywords().contains(w))
                    .sorted()
                    .toList();
            assertTrue(fehlen.isEmpty(), () -> "Im Highlighter fehlen: " + fehlen);
        }

        @Test
        @DisplayName("Einfaerben loest keine weiteren Aenderungs-Events aus")
        void einfaerben_erzeugt_keine_ereignisschleife() throws Exception {
            // setCharacterAttributes feuert selbst Dokument-Events. Ohne
            // Reentranz-Sperre laeuft daraus ein Endlos-Zyklus, der zugleich
            // den Dialekt-Timer verhungern laesst.
            faerbe("select a, b from t where c = 1 -- x");
            Timer highlightTimer = field("highlightTimer");
            Timer dialectTimer = field("dialectTimer");
            // Beide Timer anhalten, statt auf ihr Abklingen zu warten. Der
            // Dialekt-Timer wiederholt sich alle 300 ms und kann den
            // Entprell-Timer sonst genau dann neu starten, wenn asserted wird -
            // der Test war dadurch sporadisch rot. Ohne laufende Timer kann
            // ausser dem Einfaerben selbst niemand den Timer einplanen, und
            // genau das ist die Zusage.
            dialectTimer.stop();
            highlightTimer.stop();
            Thread.sleep(50);

            // setCharacterAttributes feuert technisch weiter Dokument-Events an
            // alle Listener - das laesst sich nicht unterdruecken und ist
            // unschadlich. Zaehlbar ist die Wirkung: das Panel darf sich nicht
            // erneut einplanen, sonst laeuft es im Kreis und der
            // Dialekt-Timer verhungert.
            panel.faerbeHoch();
            assertFalse(highlightTimer.isRepeats(),
                    "Einfaerben muss entprellt werden, nicht wiederholt");
            assertFalse(highlightTimer.isRunning(),
                    "Einfaerben hat sich selbst wieder eingeplant - Endlosschleife");
            // Ohne die Sperre wuerde hier der Dialekt-Timer im 120-ms-Takt
            // zurueckgesetzt und seine 300 ms nie erreichen: die
            // Wirkungsanzeige bliebe fuer immer auf "—".
            assertTrue(dialectTimer.isRepeats());
        }

        @Test
        @DisplayName("das Textfeld bricht nicht um und bleibt kompakt")
        void textfeld_bricht_nicht_um_und_bleibt_kompakt() {
            JTextPane pane = (JTextPane) sqlArea;
            assertFalse(pane.getScrollableTracksViewportWidth(),
                    "SQL soll nicht umbrechen - ein umgebrochener Schluessel ist nicht lesbar");
            // Vorher PreferredSize 760x420, daraus pack() ein 626 px hohes Fenster.
            assertTrue(pane.getPreferredSize().height < 320,
                    "Textfeld ist " + pane.getPreferredSize().height
                            + " px hoch und laesst die halbe Leere im Fenster");
        }
    }

    @Nested
    @DisplayName("Statusmeldungen als Toast")
    class Toast {

        @Test
        @DisplayName("Erfolg blendet sich aus, Warnung bleibt stehen")
        void erfolg_blendet_sich_aus_warnung_bleibt() throws Exception {
            Timer toastTimer = field("toastTimer");

            sqlArea.setText("Einkaufsliste");
            assertTrue(statusLabel.getText().startsWith("\u26a0"), "Text sieht nicht nach SQL aus");
            assertFalse(toastTimer.isRunning(),
                    "eine blockierende Warnung darf nicht von selbst verschwinden");

            sqlArea.setText("select 1");
            formatButton.doClick();
            warteAufErgebnis();
            assertTrue(statusLabel.getText().startsWith("\u2713"), statusLabel.getText());
            assertTrue(toastTimer.isRunning(), "Erfolg soll ausblenden");
            assertFalse(toastTimer.isRepeats(), "Toast darf nicht wiederholen");
            assertEquals(4000, toastTimer.getDelay(), "Toast-Frist");
        }

        @Test
        @DisplayName("die Erfolgsmeldung verschwindet wieder")
        void erfolgsmeldung_verschwindet() throws Exception {
            Timer toastTimer = field("toastTimer");
            sqlArea.setText("select 1");
            formatButton.doClick();
            warteAufErgebnis();
            assertFalse(statusLabel.getText().isEmpty());

            // Echter Timer mit echter Verzoegerung. Ein Test, der den Timer
            // testweise auf 5 ms stellt, prueft nicht mehr die tatsaechliche
            // Wartezeit - und war hier auch nicht verlaesslich.
            boolean verschwunden = false;
            for (int i = 0; i < 350 && !verschwunden; i++) {
                Thread.sleep(20);
                verschwunden = leseAufEdt(statusLabel::getText).isEmpty();
            }
            String diagnose = "Erfolgsmeldung blieb stehen: laeuft=" + toastTimer.isRunning()
                    + " wiederholt=" + toastTimer.isRepeats()
                    + " verzoegerung=" + toastTimer.getDelay()
                    + " text='" + leseAufEdt(statusLabel::getText) + "'";
            assertTrue(verschwunden, diagnose);
        }
    }


    /** Setzt Text und faerbt sofort, ohne den 120-ms-Timer abzuwarten. */
    private void faerbe(String text) {
        sqlArea.setText(text);
        panel.faerbeHoch();
    }

    private int ab(String teil) {
        return sqlArea.getText().indexOf(teil);
    }

    /** Erste Stelle von {@code teil}, optional ab einem Startindex. */
    private int ab(String teil, int ab) {
        return sqlArea.getText().indexOf(teil, ab);
    }

    private StyledDocument document() {
        // getStyledDocument() sitzt auf JTextPane, nicht auf JTextComponent.
        return ((JTextPane) sqlArea).getStyledDocument();
    }

    private Color farbeAn(String teil) {
        int pos = ab(teil);
        assertTrue(pos >= 0, "'" + teil + "' steht nicht im Text: " + sqlArea.getText());
        return farbeAn(teil, pos);
    }

    private Color farbeAn(String teil, int pos) {
        assertTrue(pos >= 0, "'" + teil + "' steht nicht an " + pos);
        return StyleConstants.getForeground(document().getCharacterElement(pos).getAttributes());
    }

    private boolean kursivAn(String teil) {
        return StyleConstants.isItalic(document().getCharacterElement(ab(teil)).getAttributes());
    }

    private void aufEdt(Runnable aktion) throws Exception {
        SwingUtilities.invokeAndWait(aktion);
    }

    private static double contrast(Color a, Color b) {
        double l1 = luminance(a);
        double l2 = luminance(b);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed() / 255.0)
                + 0.7152 * channel(c.getGreen() / 255.0)
                + 0.0722 * channel(c.getBlue() / 255.0);
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /**
     * Wartet, bis der Formatier-Worker seine Statusmeldung gesetzt hat, und
     * liest dabei jedes Mal ueber den EDT - ein Swing-Component darf von
     * aussen nicht direkt angefasst werden.
     */
    private void warteAufErgebnis() throws Exception {
        for (int i = 0; i < 500; i++) {
            String status = leseAufEdt(statusLabel::getText);
            if (status.startsWith("\u2713") || status.startsWith("\u26a0")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Formatierung wurde nicht fertig: " + statusLabel.getText());
    }

    private <T> T leseAufEdt(java.util.function.Supplier<T> leser) throws Exception {
        Object[] ergebnis = new Object[1];
        SwingUtilities.invokeAndWait(() -> ergebnis[0] = leser.get());
        @SuppressWarnings("unchecked")
        T wert = (T) ergebnis[0];
        return wert;
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) throws Exception {
        Field f = FormatterPanel.class.getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(panel);
    }
}
