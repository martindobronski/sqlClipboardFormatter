package signaliduna;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    private JTextArea sqlArea;
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
        void beschriftung_und_farbe() {
            assertEquals("Beenden", exitButton.getText());
            assertEquals(new Color(0xB23C32), exitButton.getBackground());
            assertEquals(Color.WHITE, exitButton.getForeground());
        }

        @Test
        @DisplayName("steht als vierter Button rechts von den drei Aktionen")
        void steht_rechts_als_vierter() {
            assertInstanceOf(GridBagLayout.class, exitButton.getParent().getLayout());
            GridBagConstraints gbc = ((GridBagLayout) exitButton.getParent().getLayout())
                    .getConstraints(exitButton);
            GridBagConstraints readGbc = ((GridBagLayout) exitButton.getParent().getLayout())
                    .getConstraints(readButton);

            assertEquals(3, gbc.gridx, "Beenden muss Spalte 3 (vierte) sein");
            assertEquals(0, readGbc.gridx);
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

            assertEquals(new Color(0x505050), formatButton.getForeground());
            assertEquals(new Color(0xD6D6D6), formatButton.getBackground());
            // Vorher: weisse Schrift auf LIGHT_GRAY = 1,82:1
            assertTrue(contrast(formatButton.getBackground(), formatButton.getForeground()) > 4.5,
                    "Kontrast muss WCAG AA (4.5:1) erfuellen");
        }

        @Test
        void aktive_buttons_tragen_ihre_farbe_mit_weisser_schrift() {
            sqlArea.setText("select 1");

            assertEquals(new Color(0x2E8B57), formatButton.getBackground());
            assertEquals(Color.WHITE, formatButton.getForeground());
        }

        @Test
        void alle_drei_buttons_sind_vorhanden() {
            assertEquals("Clipboard einlesen", readButton.getText());
            assertEquals("SQL Formatieren", formatButton.getText());
            assertEquals("Ins Clipboard schreiben", writeButton.getText());
        }
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
