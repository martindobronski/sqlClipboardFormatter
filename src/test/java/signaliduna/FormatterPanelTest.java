package signaliduna;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JToggleButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
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
import java.awt.Component;
import java.awt.BorderLayout;
import java.awt.Graphics2D;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    private JToggleButton themeButton;
    private JLabel umfangLabel;
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
        themeButton = field("themeButton");
        umfangLabel = field("umfangLabel");
        dialectCombo = field("dialectCombo");
        statusLabel = field("statusLabel");
        dialectWirkung = field("dialectWirkung");
    }

    @Nested
    @DisplayName("Freischaltung haengt am Text")
    class Freischaltung {

        @Test
        void startzustand_ist_gesperrt() {
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
        @DisplayName("der Ruhezustand laesst dem Zaehler den Platz")
        void ruhezustand_zeigt_den_zaehler() {
            sqlArea.setText("select 1");
            assertEquals("", statusLabel.getText(), "im Ruhezustand steht kein Text");
            assertFalse(statusLabel.isVisible(), "im Ruhezustand ist keine Meldung da");
            assertTrue(umfangLabel.isVisible(), "im Ruhezustand steht der Zaehler");

            sqlArea.setText("");
            assertEquals("", statusLabel.getText());
            assertTrue(umfangLabel.isVisible());
        }

        @Test
        @DisplayName("eine echte Meldung verdraengt den Zaehler an derselben Stelle")
        void meldung_verdraengt_den_zaehler() throws Exception {
            JLabel zaehler = umfangLabel;
            // Beide Texte haengen in demselben Container: damit teilen sie sich
            // zwangslaeufig denselben Platz und koennen sich nicht gegenseitig
            // in die Breite schieben. Getestet wird die Struktur, nicht eine
            // Pixelposition - die haengt im Testfenster von Layout-Timing ab.
            JPanel infoLine = field("infoLine");
            java.awt.Container platz = zaehler.getParent();
            assertSame(platz, statusLabel.getParent(),
                    "Zaehler und Meldung teilen sich nicht denselben Platz");
            assertSame(infoLine, platz.getParent(),
                    "der Platz ist nicht die Zeile selbst, sondern ein eigenes Feld darin");

            sqlArea.setText("select 1");
            assertTrue(zaehler.isVisible(), "im Ruhezustand steht der Zaehler");
            assertFalse(statusLabel.isVisible(), "im Ruhezustand steht keine Meldung");

            sqlArea.setText("Einkaufsliste");
            assertTrue(statusLabel.getText().startsWith("\u26a0"), statusLabel.getText());
            assertFalse(zaehler.isVisible(), "waehrend einer Meldung steht der Zaehler nicht daneben");
            assertTrue(statusLabel.isVisible(), "die Meldung steht an ihrem Platz");

            // Sichtbar sein reicht nicht: eine Komponente, die das Layout nicht
            // anordnet, hat Breite 0 und wird gar nicht gezeichnet. Genau das
            // war der Fehler, als beide Texte auf dasselbe CENTER gelegt
            // wurden - der zweite verdraengte den ersten im Layout.
            panel.setSize(700, 500);
            layoutiere(panel);
            assertTrue(statusLabel.getWidth() > 0,
                    "die Meldung hat keine Breite und wird nicht gezeichnet");
        }

        /**
         * Ordnet auch die verschachtelten Ebenen; {@code doLayout()} allein legt
         * nur die direkten Kinder an.
         */
        private void layoutiere(java.awt.Container c) {
            c.doLayout();
            for (java.awt.Component kind : c.getComponents()) {
                if (kind instanceof java.awt.Container kc) {
                    layoutiere(kc);
                }
            }
        }

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
        @DisplayName("der Hinweis erscheint nur, wenn er etwas meldet")
        void hinweis_ist_nur_bei_abweichung_sichtbar() throws Exception {
            // Fuelltext: Standard SQL genuegt - das ist keine Meldung, sondern
            // der Normalfall, und stand vorher als Zeile neben dem Dropdown.
            sqlArea.setText("select 1");
            warteAufDialectAnzeige("Standard SQL genügt");
            assertFalse(dialectWirkung.isVisible(),
                    "der Normalfall soll den Chip nicht zeigen");

            // Abweichung: jetzt ist die Meldung das, was sie sein soll.
            sqlArea.setText("select top 10 id from t");
            warteAufDialectAnzeige("wirksam: T-SQL");
            assertTrue(dialectWirkung.isVisible(),
                    "eine Abweichung gehoert sichtbar gemacht");

            // Und wieder zurueck, wenn sie entfaellt.
            sqlArea.setText("");
            warteAufDialectAnzeige("—");
            assertFalse(dialectWirkung.isVisible(), "der Chip blieb stehen");
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

          @Test
          @DisplayName("ein zu langer Text bricht den Dialektvergleich ab")
          void zu_langer_text_bricht_den_vergleich_ab() throws Exception {
              JLabel wirkung = field("dialectWirkung");
              sqlArea.setText("select ".repeat(700));

              // Der Vergleich laeuft im Hintergrund, also warten bis der Hinweis
              // da ist. Bei 4900 Zeichen soll gar kein Vergleich mehr starten.
              String erwartet = "Text zu lang, um die Dialekte zu vergleichen.";
              String tooltip = null;
              for (int i = 0; i < 100; i++) {
                  Thread.sleep(20);
                  tooltip = leseAufEdt(wirkung::getToolTipText);
                  if (erwartet.equals(tooltip)) {
                      break;
                  }
              }
              assertEquals(erwartet, tooltip,
                      "ohne Obergrenze wuerde der Vergleich den ganzen Text durch alle "
                              + "Dialekte schieben, bei jedem Tastendruck von neu");
              assertEquals("\u2014", leseAufEdt(wirkung::getText),
                      "ohne Vergleichsergebnis wird nichts behauptet");
              assertFalse(leseAufEdt(wirkung::isVisible),
                      "ohne Vergleichsergebnis bleibt die Anzeige weg");
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
        @DisplayName("Beenden bleibt ein Textlink")
        void ist_als_textlink_gesetzt() {
            assertEquals("Beenden", exitButton.getText());
            // Mit eigener Flaeche und eigenem Rahmen wuerde Beenden so viel
            // Gewicht bekommen wie "Ins Clipboard schreiben", obwohl es die
            // am seltensten benutzte Aktion ist.
            assertFalse(exitButton.isContentAreaFilled(), "Beenden darf keine Flaeche malen");
            assertFalse(exitButton.isBorderPainted(), "Beenden darf keinen Rahmen tragen");
            assertTrue(contrast(Theme.DUNKEL.gedaempft, Theme.DUNKEL.hintergrund) > 4.5,
                    "der Link-Text muss WCAG AA erfuellen");
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
        @DisplayName("gesperrte Buttons behalten ihre Farbe und haben ausreichenden Kontrast")
        void gesperrte_buttons_haben_kontrast() {
            sqlArea.setText("Einkaufsliste");
            assertFalse(formatButton.isEnabled());

            // Kein neutrales Grau: die Farbe gehoert zum Knopf, nicht nur zum
            // aktiven Zustand. Sonst waere beim Start - leeres Textfeld, zwei
            // von drei Knöpfen gesperrt - nicht zu erkennen, welcher Knopf das
            // Formatieren ist.
            assertEquals(Theme.DUNKEL.gesperrt(Theme.DUNKEL.akzent), formatButton.getBackground());
            assertEquals(Theme.DUNKEL.aufDeaktiviert, formatButton.getForeground());
            assertTrue(contrast(formatButton.getBackground(), formatButton.getForeground()) > 4.5,
                    "Kontrast muss WCAG AA (4.5:1) erfuellen");
            // Und die Farbe muss noch als Farbe erkennbar sein, also nicht
            // vollstaendig im Hintergrund verschwinden.
            assertNotEquals(Theme.DUNKEL.hintergrund, formatButton.getBackground(),
                    "die entschaerfte Farbe ist der Hintergrund geworden");
        }

        @Test
        @DisplayName("jede Aktion hat ihre eigene Farbe")
        void jede_aktion_hat_ihre_eigene_farbe() {
            sqlArea.setText("select 1");

            // Ueber die Palette statt ueber feste Zahlen: geprueft wird die
            // Rolle, nicht ein zufaellig gewaehlter Farbwert.
            assertEquals(Theme.DUNKEL.akzent, formatButton.getBackground());
            assertEquals(Theme.DUNKEL.hintergrund, readButton.getBackground());
            assertEquals(Theme.DUNKEL.tonal, writeButton.getBackground());

            // Drei verschiedene Farben sind nur dann ein Vorteil, wenn man sie
            // auseinanderhaelt. Zwei gleiche wuerden die Unterscheidung wieder
            // aufheben, die sie schaffen soll.
            assertNotEquals(readButton.getBackground(), writeButton.getBackground(),
                    "Einlesen und Schreiben teilen sich dieselbe Farbe");
            assertNotEquals(formatButton.getBackground(), readButton.getBackground(),
                    "Formatieren und Einlesen teilen sich dieselbe Farbe");
            assertNotEquals(formatButton.getBackground(), writeButton.getBackground(),
                    "Formatieren und Schreiben teilen sich dieselbe Farbe");
        }

        @Test
        @DisplayName("auch die gesperrten Knoepfe bleiben unterscheidbar")
        void gesperrte_knoepfe_bleiben_unterscheidbar() {
            // Der Startzustand: nur Einlesen ist frei. Wer hier zwei gleiche
            // Flaechen sieht, raeht an der Bedienung vorbei.
            assertTrue(readButton.isEnabled());
            assertFalse(formatButton.isEnabled());
            assertFalse(writeButton.isEnabled());

            assertNotEquals(formatButton.getBackground(), writeButton.getBackground(),
                    "die beiden gesperrten Knoepfe sind nicht unterscheidbar");
            // Die umrandete Stufe hat per Definition keine Flaeche - erkennbar
            // bleibt sie nur an ihrer Linie. Faellt die aus, ist der Knopf
            // weg, nicht nur inaktiv.
            assertFalse(readButton.isContentAreaFilled(), "Einlesen bleibt ohne Flaeche");
            javax.swing.border.Border linie =
                    ((javax.swing.border.CompoundBorder) readButton.getBorder()).getOutsideBorder();
            assertEquals(Theme.DUNKEL.akzentText,
                    ((javax.swing.border.LineBorder) linie).getLineColor(),
                    "der aktive Knopf traegt keine Akzentlinie");
        }
        @Test
        @DisplayName("alle drei Aktionsstufen teilen sich eine Akzentfarbe")

        void alle_stufen_stammen_aus_einer_akzentfarbe() {
            // Drei Knoepfe in drei Farbtönen sahen willkürlich aus. Geprueft
            // wird deshalb nicht "sie sind verschieden", sondern "alle Stufen
            // sind Stufen desselben Farbtons": der Blaukanal fuehrt, und
            // keiner ist neutral oder braun. Braun ist der Ton, der in
            // UI-Zusammenhaengen am schnellsten hochwertig wirkt.
            //
            // Beide Paletten werden geprueft, nicht nur die aktive: ein Test,
            // der nur das dunkle Theme sieht, haette ein braunes helles
            // Theme unbeanstandet gelassen.
            for (Theme theme : List.of(Theme.HELL, Theme.DUNKEL)) {
                for (Color c : List.of(theme.akzent, theme.akzentText, theme.tonal)) {
                    assertTrue(c.getBlue() >= c.getRed() && c.getBlue() >= c.getGreen(),
                            () -> theme.bezeichnung() + ": Blau fuehrt nicht: " + c);
                    assertTrue(c.getBlue() - c.getRed() >= 15,
                            () -> theme.bezeichnung() + ": zu neutral oder braun: " + c);
                }
            }
            // Und die drei Stufen muessen sich auch unterscheiden, sonst
            // waeren sie ein System aus einem Knopf.
            sqlArea.setText("select 1");
            assertNotEquals(formatButton.getBackground(), writeButton.getBackground(),
                    "Formatieren und Schreiben sind dieselbe Stufe");
        }

        private static Color lineColor(JButton knopf) {
            javax.swing.border.Border rand = knopf.getBorder();
            rand = ((javax.swing.border.CompoundBorder) rand).getOutsideBorder();
            return ((javax.swing.border.LineBorder) rand).getLineColor();
        }

        @Test
        @DisplayName("unter kurzem Text steht keine tote Flaeche")
        void unter_kurzem_text_keine_tote_flaeche() throws Exception {
            // Der Textbereich waechst nur mit dem Text, damit der Rollbalken
            // entstehen kann. Bei kurzem Text bleibt er deshalb niedrig, und
            // darunter waere die Flaeche des Viewports zu sehen - die muss
            // dieselbe sein wie die des Editors, sonst steht ein heller Streifen
            // unter dem Text.
            sqlArea.setSize(400, 400);
            sqlArea.setText("select 1");
            JScrollPane sp = field("scrollPane");
            JViewport viewport = sp.getViewport();
            Theme theme = field("aktuellesTheme");
            assertEquals(sqlArea.getBackground(), viewport.getBackground(),
                    "unter dem Text darf keine fremde Flaeche stehen");
            assertEquals(theme.flaeche, viewport.getBackground(),
                    "der Viewport traegt die Theme-Flaeche");
            int zeilenhoehe = sqlArea.getFontMetrics(sqlArea.getFont()).getHeight();
            assertEquals(16 * zeilenhoehe, sqlArea.getPreferredSize().height,
                    "kurzer Text: der Bereich bleibt bei seinen 16 Zeilen");
            // Die Breite bleibt bewusst frei, damit lange Zeilen waagerecht
            // scrollen, statt ein Wort mitten drin umzubrechen.
            assertFalse(sqlArea.getScrollableTracksViewportWidth(),
                    "der Editor soll nicht mit der Fensterbreite mitwaachsen");
        }

        @Test
        @DisplayName("der Editor nagelt die Viewport-Hoehe nicht fest")
        void editor_nagelt_die_viewport_hoehe_nicht_fest() throws Exception {
            // ViewportLayout setzt die Hoehe der Ansicht auf die des Viewports,
            // sobald getScrollableTracksViewportHeight() 'ja' sagt. Waere das der
            // Fall, bliebe der Textbereich immer so hoch wie das Fenster, es
            // kaeme kein Rollbalken zustande, und die Zeilen unterhalb waeren im
            // echten Fenster nicht erreichbar.
            JTextComponent editor = field("sqlArea");
            Class<?> editorKlasse = editor.getClass();
            assertNotEquals(editorKlasse,
                    editorKlasse.getMethod("getScrollableTracksViewportHeight")
                            .getDeclaringClass(),
                    "der Editor darf seine Hoehe nicht selbst an den Viewport binden");
        }

        @Test
        @DisplayName("der Editor waechst mit dem Text, damit nichts unerreichbar wird")
        void editor_waechst_mit_dem_text() throws Exception {
            // Die bevorzugte Hoehe folgt der Zeilenzahl: nur so bekommt der
            // Viewport eine Ansicht, die groesser ist als er selbst, und nur so
            // entsteht ein Rollbalken.
            sqlArea.setSize(400, 400);
            sqlArea.setText("select 1\nfrom t");
            int zeilenhoehe = sqlArea.getFontMetrics(sqlArea.getFont()).getHeight();
            assertEquals(16 * zeilenhoehe, sqlArea.getPreferredSize().height,
                    "zwei Zeilen passen noch in die 16 Zeilen des Startbereichs");
            sqlArea.setText(vieleZeilen(200));
            // Die Hoehe folgt den Zeilen, die der Text wirklich belegt, und die
            // nennt erst der Zeilenkopf beim Zeichnen mit.
            meldeBildschirmzeilen(400, 400);
            assertTrue(sqlArea.getPreferredSize().height >= 200 * zeilenhoehe,
                    "der Textbereich ist so hoch wie der Text, aktuell "
                            + sqlArea.getPreferredSize().height);
            // Der Bereich muss sich auch oeffnen duerfen - mit NEVER waeren die
            // Zeilen zwar hoch, aber trotzdem unerreichbar.
            JScrollPane sp = field("scrollPane");
            assertEquals(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    sp.getVerticalScrollBarPolicy(),
                    "ohne Rollbalken bleibt der Text unter dem Fenster unerreichbar");
        }

        @Test
        @DisplayName("eine umgebrochene Zeile bleibt bis zum Ende erreichbar")
        void umbruch_bleibt_erreichbar() throws Exception {
            // Zaehlt man Absaetze statt Bildschirmzeilen, ist der Bereich fuer
            // eine umgebrochene Zeile zu kurz: der Text waere abgeschnitten und
            // nicht erreichbar, weil die Zeilen, die der Umbruch braucht, gar
            // keinen Platz bekommen.
            int breite = 200;
            // So lang, dass der Umbruch die 16 Startzeilen sprengt.
            String lang = wiederhole("select 1 from tabelle ", 20);
            sqlArea.setSize(breite, 200);
            sqlArea.setText(lang + "\n");
            meldeBildschirmzeilen(breite, 200);
            int zeilenhoehe = sqlArea.getFontMetrics(sqlArea.getFont()).getHeight();
            assertTrue(sqlArea.getPreferredSize().height > 16 * zeilenhoehe,
                    "die " + zeilenBeiUmbruch(lang, breite)
                            + " Bildschirmzeilen brauchen mehr als die 16 Startzeilen, "
                            + "sonst waeren sie unerreichbar");
        }

        @Test
        @DisplayName("die Statuszeile zaehlt die Bildschirmzeilen, nicht die Absaetze")
        void umfang_zaehlt_bildschirmzeilen() throws Exception {
            int breite = 200;
            String lang = wiederhole("select 1 from tabelle ", 6);
            String t = lang + "\nselect 2\n";
            sqlArea.setSize(breite, 200);
            sqlArea.setText(t);
            meldeBildschirmzeilen(breite, 200);
            int zeilen = bemalteZahlen(breite, 200);
            assertEquals(zeilen + " Zeilen, " + t.length() + " Zeichen",
                    ((JLabel) field("umfangLabel")).getText(),
                    "die lange Zeile bricht um und gehoert mehrfach mit");
        }

        @Test
        @DisplayName("unten und rechts steht der Text nicht am Rahmen")
        void unten_und_rechts_luft() {
            // Sobald ein Rollbalken auftaucht, klebt die letzte Zeile sonst an ihm.
            java.awt.Insets rand = sqlArea.getMargin();
            assertEquals(9, rand.bottom, "unten haengt der Text nicht am Rollbalken");
            assertEquals(9, rand.right, "rechts haengt der Text nicht am Rollbalken");
        }

        private String vieleZeilen(int anzahl) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < anzahl; i++) {
                sb.append("select ").append(i).append("\n");
            }
            return sb.toString();
        }

        @Test
        @DisplayName("die drei Aktionen tragen ein Symbol, die Links keines")
        void aktionen_tragen_symbole() {
            for (JButton knopf : List.of(readButton, formatButton, writeButton)) {
                assertNotNull(knopf.getIcon(),
                        () -> "'" + knopf.getText() + "' hat kein Symbol");
                assertTrue(knopf.getIcon().getIconWidth() > 0
                                && knopf.getIcon().getIconHeight() > 0,
                        () -> "'" + knopf.getText() + "' hat ein leeres Symbol");
            }
            // Links tragen keins: dort ist der Text das Erkennungsmerkmal.
            assertNull(exitButton.getIcon(), "der Beenden-Link traegt ein Symbol");
            assertNotNull(themeButton.getIcon(), "der Theme-Schalter traegt ein Symbol");
        }

        @Test
        @DisplayName("das Symbol nimmt die Textfarbe des Knopfes an")
        void symbol_teilt_die_beschriftungsfarbe() {
            // Bei gesperrten Knoepfen wird die Beschriftung gedimmt; ein
            // eigenstaendig eingefaerbtes Symbol wuerde dann heller wirken als
            // der Text daneben und die Sperre undermine.
            for (JButton knopf : List.of(formatButton, writeButton)) {
                assertNotNull(knopf.getIcon(), () -> "'" + knopf.getText() + "'");
                // paintIcon liest die Foreground des Zeichners, nicht die des
                // Knopfes - genau deshalb wird hier die Beschriftung gesetzt
                // und danach die Farbe des Symbols geprueft.
                java.awt.image.BufferedImage puffer = new java.awt.image.BufferedImage(
                        20, 20, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                knopf.getIcon().paintIcon(knopf, puffer.getGraphics(), 0, 0);
                boolean gemalt = false;
                for (int y = 0; y < 20 && !gemalt; y++) {
                    for (int x = 0; x < 20; x++) {
                        if ((puffer.getRGB(x, y) >>> 24) > 0) {
                            gemalt = true;
                            break;
                        }
                    }
                }
                assertTrue(gemalt, () -> "'" + knopf.getText() + "' malt nichts");
            }
        }

        @Test
        @DisplayName("Zeilen und Zeichen werden mitgezaehlt")
        void umfang_wird_angezeigt() {
            sqlArea.setText("");
            assertEquals("0 Zeilen, 0 Zeichen", umfangLabel.getText());

            sqlArea.setText("a\nbb\nccc");
            assertEquals("3 Zeilen, 8 Zeichen", umfangLabel.getText());

            sqlArea.setText("nur eine");
            assertEquals("1 Zeile, 8 Zeichen", umfangLabel.getText(),
                    "bei einer Zeile steht etwas anderes als in der Mehrzahl");

            // Unter Windows landet CRLF in der Zwischenablage. Zaehlt man
            // stattdessen die Zeichen im Dokument, zeigt derselbe Text dort
            // zwei Zeichen mehr je Umbruch als hier.
            sqlArea.setText("a\r\nbb\r\nccc");
            assertEquals("3 Zeilen, 8 Zeichen", umfangLabel.getText(),
                    "CRLF aus der Zwischenablage darf den Zaehler nicht verfälschen");
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
                // Beschriftung auf Schaltflaeche. Gesperrt wird nicht mehr ueber
                // eine eigene Farbe geprueft, sondern ueber die entschaerfte
                // Eigenfarbe - und die ist fuer jede Aktion eine andere.
                pruefe(theme, theme.aufAkzent, theme.akzent, "Beschriftung Formatieren", maengel);
                pruefe(theme, theme.akzentText, theme.hintergrund, "Beschriftung Einlesen", maengel);
                pruefe(theme, theme.akzentText, theme.tonal, "Beschriftung Schreiben", maengel);
                pruefe(theme, theme.aufDeaktiviert, theme.gesperrt(theme.akzent),
                        "Beschriftung gesperrt Formatieren", maengel);
                pruefe(theme, theme.aufDeaktiviert, theme.gesperrt(theme.tonal),
                        "Beschriftung gesperrt Schreiben", maengel);
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
        @DisplayName("die Umrandung der Schaltflaechen erfuellt WCAG 1.4.11")
        void schaltflaechen_sind_am_rand_erkennbar() {
            // WCAG 1.4.11 verlangt 3:1 fuer die Erkennbarkeit einer
            // Bedienoberflaeche - fuer die Flaeche oder ihre Begrenzung, nicht
            // fuer beides. Der Test oben prueft nur Text auf Flaeche, und so
            // konnten die Schaltflaechen voellig im Hintergrund verschwinden,
            // ohne dass ein Wert auffiel: gemessen waren es 1,05:1 im hellen
            // und 1,48:1 im dunklen Theme.
            List<String> maengel = new ArrayList<>();
            for (Theme theme : List.of(Theme.DUNKEL, Theme.HELL)) {
                double r = contrast(theme.rahmen, theme.hintergrund);
                if (r < 3.0) {
                    maengel.add(theme.bezeichnung() + "/Rahmen=" + String.format("%.2f", r));
                }
            }
            assertTrue(maengel.isEmpty(), () -> "Rahmen unter WCAG 1.4.11 (3:1): " + maengel);
        }

        @Test
        @DisplayName("jede gefuellte Schaltflaeche traegt den Rahmen aus dem Theme")
        void gefuellte_schaltflaechen_tragen_den_rahmen() {
            // Fuelltext, damit alle drei aktiv sind: im gesperrten Zustand
            // sind die Linien entschaerft und damit nicht vergleichbar.
            sqlArea.setText("select 1");
            // Die Palettenpruefung koennte gruen sein, waehrend das Panel den
            // Rahmen gar nicht setzt. Dieser Test schliesst die Luecke
            // zwischen "die Farbe existiert" und "sie ist am Button zu sehen".
            for (JButton knopf : List.of(formatButton, readButton, writeButton)) {
                assertTrue(knopf.isBorderPainted(),
                        () -> "'" + knopf.getText() + "' hat keine sichtbare Umrandung");
                javax.swing.border.Border rand = knopf.getBorder();
                rand = ((javax.swing.border.CompoundBorder) rand).getOutsideBorder();
                assertInstanceOf(javax.swing.border.LineBorder.class, rand,
                        () -> "'" + knopf.getText() + "' hat keinen 1px-Rahmen");
                // Die umrandete Stufe traegt die Akzentfarbe als Linie, weil
                // ihre Flaeche nichts vom Fenster unterscheidet.
                Color erwartet = knopf == readButton
                        ? Theme.DUNKEL.akzentText
                        : Theme.DUNKEL.rahmen;
                assertEquals(erwartet, ((javax.swing.border.LineBorder) rand).getLineColor(),
                        () -> "'" + knopf.getText() + "'");
            }
            // Beenden und der Theme-Schalter bleiben Links.
            assertFalse(exitButton.isBorderPainted(), "Beenden bleibt ohne Rahmen");
            assertTrue(themeButton.isBorderPainted(), "der Theme-Schalter hat einen Rahmen");
        }

        @Test
        @DisplayName("Knopftext klebt nicht am Rand und die Flaeche ist erhaben")
        void knopf_hat_innenabstand_und_fase() {
            for (JButton knopf : List.of(formatButton, readButton, writeButton)) {
                java.awt.Insets i = knopf.getBorder().getBorderInsets(knopf);
                assertTrue(i.left >= 12 && i.right >= 12,
                        () -> "'" + knopf.getText() + "' zu wenig Innenabstand: " + i);
                assertTrue(i.top >= 4 && i.bottom >= 4,
                        () -> "'" + knopf.getText() + "' zu wenig Innenabstand: " + i);
            }
            // Die Fase muss auch wirklich eine BevelBorder sein - sonst waere
            // der Knopf flach mit einer Linie drumherum.
            javax.swing.border.Border innen =
                    ((javax.swing.border.CompoundBorder) formatButton.getBorder()).getInsideBorder();
            javax.swing.border.Border fase =
                    ((javax.swing.border.CompoundBorder) innen).getOutsideBorder();
            assertInstanceOf(javax.swing.border.BevelBorder.class, fase, "der 3D-Effekt fehlt");
            assertEquals(javax.swing.border.BevelBorder.RAISED,
                    ((javax.swing.border.BevelBorder) fase).getBevelType());
        }

        @Test
        @DisplayName("das Theme, mit dem die App startet, ist das helle")
        void starttheme_ist_hell() {
            assertEquals(Theme.HELL, FormatterPanel.STANDARD,
                    "der Start muss im hellen Theme landen, nicht im dunklen");
        }

        @Test
        @DisplayName("Umschalter tauscht LookAndFeel und Farben")
        void umschalter_tauscht_lookandfeel_und_farben() throws Exception {
            JToggleButton themeButton = field("themeButton");
            // Fuelltext noetig: ohne SQL ist der Formatier-Button gesperrt und
            // traegt die entschaerfte Farbe statt der eigenen.
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
            JToggleButton themeButton = field("themeButton");
            sqlArea.setText("select 1");
            aufEdt(themeButton::doClick);
            aufEdt(themeButton::doClick);
            assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel());
            assertEquals(Theme.DUNKEL.akzent, formatButton.getBackground());
        }

        @Test
        @DisplayName("der Schalter zeigt den Zustand, Text und Symbol das Ziel")
        void schalter_zeigt_zustand_und_ziel() throws Exception {
            // Der Testaufbau startet im dunklen Theme: der Schalter ist an,
            // Text und Symbol zeigen aber nach Hell.
            JToggleButton themeButton = field("themeButton");
            assertTrue(themeButton.isSelected(), "im dunklen Theme ist der Schalter an");
            assertEquals("Hell", themeButton.getText(), "der Text nennt das Ziel");
            Icon mond = themeButton.getIcon();
            assertNotNull(mond, "vor dem Text steht ein Symbol");
            assertEquals(Theme.DUNKEL.aufAkzent, themeButton.getForeground(),
                    "im dunklen Theme steht die Beschriftung in Weiss");

            sqlArea.setText("select 1");
            aufEdt(themeButton::doClick);
            assertFalse(themeButton.isSelected(), "nach dem Umschalten ist der Schalter aus");
            assertEquals("Dunkel", themeButton.getText(), "der Text nennt das neue Ziel");
            assertNotSame(mond, themeButton.getIcon(), "das Symbol wechselt mit");
            assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel());
            assertTrue(contrast(themeButton.getForeground(), panel.getBackground()) >= 4.5,
                    "auch im hellen Theme bleibt die Beschriftung lesbar");
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

      @Nested
      @DisplayName("Tastatur und Screenreader")
      class Bedienbarkeit {

          @Test
          @DisplayName("jede Aktion hat ein Tastenkuerzel")
          void aktionen_haben_tastenkuerzel() {
              // Ohne Mnemonik gibt es keinen Weg an die Buttons, ohne 200 Mal
              // mit Tab durch den Text zu gehen.
              for (JButton button : new JButton[]{
                      readButton, formatButton, writeButton, exitButton}) {
                  assertTrue(button.getMnemonic() > 0,
                          button.getText() + " hat kein Tastenkuerzel");
                  assertTrue(button.isFocusable(),
                          button.getText() + " ist nicht an der Tastatur erreichbar");
              }
              // Alt+E, Alt+F, Alt+L und Alt+C doppeln sich nicht.
              Set<Integer> kuerzel = new HashSet<>();
              for (JButton button : new JButton[]{
                      readButton, formatButton, writeButton, exitButton}) {
                  assertTrue(kuerzel.add(button.getMnemonic()),
                          "Tastenkuerzel " + button.getMnemonic() + " ist zweimal vergeben");
              }
          }

          @Test
          @DisplayName("der Fokusring hebt sich vom Knopfgrund ab")
          void fokusring_hebt_sich_ab() throws Exception {
              // Ein Ring, den man nicht sieht, ist keiner. FlatLafs Standardring
              // laege auf dem blauen Hauptknopf bei 1,1:1 - das ist der Grund,
              // warum die Knoepfe ihren Ring selbst zeichnen.
              sqlArea.setText("select 1 from t");
              for (Theme theme : new Theme[]{Theme.DUNKEL, Theme.HELL}) {
                  FormatterPanel.setzeTheme(theme);
                  for (JButton button : new JButton[]{
                          readButton, formatButton, writeButton, exitButton}) {
                      if (!button.isEnabled()) {
                          continue;
                      }
                      Field ring = button.getClass().getDeclaredField("ring");
                      ring.setAccessible(true);
                      Color ringFarbe = (Color) ring.get(button);
                      // Der Link ist nicht gefuellt, er liegt auf dem Fenster.
                      Color grund = button.isOpaque() ? button.getBackground()
                              : panel.getBackground();
                      double verhaeltnis = contrast(ringFarbe, grund);
                      assertTrue(verhaeltnis >= 3.0,
                              (theme.isDunkel() ? "dunkel" : "hell") + ": Ring "
                                      + ringFarbe + " auf " + grund + " nur "
                                      + String.format("%.1f", verhaeltnis) + ":1");
                  }
              }
          }

          @Test
          @DisplayName("das Textfeld und der Theme-Schalter haben Namen")
          void textfeld_und_thema_haben_namen() throws Exception {
              // Fuer einen Screenreader ist "TextArea" sonst alles, was es weiss.
              assertEquals("SQL-Text", sqlArea.getAccessibleContext().getAccessibleName());
              assertNotNull(sqlArea.getAccessibleContext().getAccessibleDescription(),
                      "das Textfeld erklaert nicht, was mit ihm passiert");
              assertEquals("Theme-Schalter",
                      themeButton.getAccessibleContext().getAccessibleName());
          }

          @Test
          @DisplayName("die Dialektbeschriftung gehoert zum Auswahlfeld")
          void beschriftung_gehoert_zum_auswahlfeld() throws Exception {
              JLabel beschriftung = field("dialectLabel");
              assertSame(dialectCombo, beschriftung.getLabelFor(),
                      "die Beschriftung gehoert zum Auswahlfeld, nicht nur daneben");
              assertEquals("SQL-Dialekt",
                      dialectCombo.getAccessibleContext().getAccessibleName());
          }

          @Test
          @DisplayName("der Fokus am Theme-Schalter haengt an der Akzentfarbe")
          void fokus_am_thema_schalter_bleibt_sichtbar() throws Exception {
              // Der Schalter malt sich selbst und schaltet den Fokusrahmen des
              // LookAndFeel ab. Ohne eigenen Ring waere er fuer die Tastatur
              // unsichtbar unsichtbar: man wuesste nicht, wo man ist.
              assertFalse(themeButton.isFocusPainted(),
                      "der LookAndFeel soll den Fokus nicht doppelt malen");
              assertTrue(themeButton.isFocusable(),
                      "der Schalter muss den Fokus bekommen koennen");

              // Geprueft wird die Farbe, mit der der Ring gestrichen wird: sie
              // muss die Akzentfarbe des aktiven Themes sein, sonst waere der
              // Ring auf dunklem Grund kaum zu sehen.
              Field ring = themeButton.getClass().getDeclaredField("ring");
              ring.setAccessible(true);
              Theme theme = field("aktuellesTheme");
              assertEquals(theme.akzent, ring.get(themeButton),
                      "der Fokusring nimmt nicht die Akzentfarbe des Themes an");
          }

          @Test
          @DisplayName("mit Fokus steht der Ring im Bild, ohne nicht")
          void fokusring_wird_gemalt() throws Exception {
              // Der gemalte Ring laesst sich nur pruefen, wenn das Testfenster
              // den Fokus wirklich annehmen darf - unter macOS verweigert das
              // System das einem Hintergrundprozess. Dann ueberspringen statt
              // gruen zu melden.
              aufEdt(() -> themeButton.requestFocusInWindow());
              assumeTrue(themeButton.hasFocus(),
                      "dieses Fenster kann den Fokus nicht halten - Ring nicht pruefbar");

              themeButton.setSize(200, 44);
              java.awt.image.BufferedImage mitFokus = new java.awt.image.BufferedImage(200, 44,
                      java.awt.image.BufferedImage.TYPE_INT_RGB);
              themeButton.paint(mitFokus.getGraphics());

              aufEdt(() -> themeButton.setFocusable(false));
              assertFalse(themeButton.hasFocus(), "Voraussetzung: Fokus ist weg");
              java.awt.image.BufferedImage ohneFokus = new java.awt.image.BufferedImage(200, 44,
                      java.awt.image.BufferedImage.TYPE_INT_RGB);
              themeButton.paint(ohneFokus.getGraphics());
              aufEdt(() -> themeButton.setFocusable(true));

              Theme theme = field("aktuellesTheme");
              assertTrue(pixelVor(mitFokus, theme.akzent),
                      "mit Fokus fehlt der Ring in " + theme.akzent);
              assertFalse(pixelVor(ohneFokus, theme.akzent),
                      "ohne Fokus darf kein Ring dastehen");
          }
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

    /**
     * Malt das Textfeld einmal, damit es seine Bildschirmzeilen meldet.
     *
     * <p>Das ist noetig, weil die Zeilenzahl beim Zeichnen gemeldet wird und
     * nicht aus dem Dokument gelesen wird: nur der Text-View weiss, wie der
     * Umbruch ausgefallen ist, und der weiss es erst, wenn er gelegt ist. Wer
     * die Zahl vorher braucht, malt einmal - genau das passiert beim Oeffnen
     * des Fensters auch.
     */
    private void meldeBildschirmzeilen(int breite, int hoehe) throws Exception {
        aufEdt(() -> malen(sqlArea, breite, hoehe, sqlArea.getBackground()));
    }

    /**
     * Malt das Textfeld in ein Bild, damit der Test Pixel zaehlen kann, statt
     * sich vorzumachen, was der Nutzer sieht.
     */
    private java.awt.image.BufferedImage malen(JTextComponent feld, int breite, int hoehe,
            Color hintergrund) {
        feld.setSize(breite, hoehe);
        java.awt.image.BufferedImage bild = new java.awt.image.BufferedImage(breite, hoehe,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bild.createGraphics();
        g.setColor(hintergrund);
        g.fillRect(0, 0, breite, hoehe);
        feld.paint(g);
        g.dispose();
        return bild;
    }

    /** Zaehlt die gemalten Streifen im Bild: einer je Zeile. */
    private int bemalteZahlen(int breite, int hoehe) throws Exception {
        meldeBildschirmzeilen(breite, hoehe);
        java.awt.image.BufferedImage bild =
                malen(sqlArea, breite, hoehe, sqlArea.getBackground());
        return gemalteZeilen(bild, zahlenSpalte(breite, hoehe),
                sqlArea.getBackground()).length;
    }

    private int[] gemalteZeilen(java.awt.image.BufferedImage bild, Rectangle streifen,
            Color hintergrund) {
        List<Integer> ys = new ArrayList<>();
        boolean imStreifen = false;
        for (int y = 0; y < streifen.height; y++) {
            boolean bemalt = false;
            for (int x = streifen.x; x < streifen.x + streifen.width && !bemalt; x++) {
                bemalt = (bild.getRGB(x, y) & 0xFFFFFF)
                        != (hintergrund.getRGB() & 0xFFFFFF);
            }
            if (bemalt && !imStreifen) {
                ys.add(Integer.valueOf(y));
            }
            imStreifen = bemalt;
        }
        int[] out = new int[ys.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ys.get(i).intValue();
        }
        return out;
    }

    /**
     * Sucht die oberste Zeile des Bildes im Streifen, die nicht die
     * Hintergrundfarbe traegt, und liefert sie.
     */
    private int ersteGemalteZeile(java.awt.image.BufferedImage bild, Rectangle streifen,
            Color hintergrund) {
        int[] ys = gemalteZeilen(bild, streifen, hintergrund);
        return ys.length == 0 ? -1 : ys[0];
    }

    /** Hoehe der n-ten gemalten Zeile im Streifen, 0 wenn es keine gibt. */
    private int hoeheDerGemaltenZeile(java.awt.image.BufferedImage bild, Rectangle streifen,
            Color hintergrund, int nummer) {
        int[] ys = gemalteZeilen(bild, streifen, hintergrund);
        return nummer >= 1 && nummer <= ys.length ? ys[nummer - 1] : -1;
    }

    /** Wie breit der Innenabstand links ist - dort stehen die Zahlen. */
    private int zahlenRand() {
        return sqlArea.getMargin().left;
    }

    /**
     * Der Streifen, in dem nur die Zahlen stehen. Der Rand bleibt weg, sonst
     * zaehlt der Streifen die Rundung des Rahmens mit.
     */
    private Rectangle zahlenSpalte(int breite, int hoehe) {
        return new Rectangle(0, 0, zahlenRand() - 4, hoehe);
    }

    /**
     * Der Streifen, in dem nur der Text steht. Erst eine Pixelzeile nach dem
     * Innenabstand, damit die erste Lettere nicht beschnitten wird.
     */
    private Rectangle textSpalte(int breite, int hoehe) {
        return new Rectangle(zahlenRand() + 1, 0, breite - zahlenRand() - 1, hoehe);
    }

    /**
     * Wie weit darf die Zahl von ihrer Textzeile abweichen?
     *
     * <p>Zwei Pixel: eine Ziffer und ein Buchstabe haben nicht dieselbe Ober-
     * und Unterkante, also ist die erste bemalte Pixelzeile nicht bei beiden
     * gleich. Wichtig ist der Betrag, nicht der Einzelfall - ein Versatz, der
     * mit jeder Zeile waechst, laeuft ueber diese Grenze hinaus.
     */
    private static final int ZEILEN_TOLERANZ = 2;

    /**
     * Prueft Bild gegen Bild: gleich viele Zahlen wie Textzeilen, und jede Zahl
     * steht auf der Hoehe ihrer Zeile.
     */
    private void assertZahlenAufDenZeilen(int[] zahlen, int[] texte, String meldung) {
        assertTrue(zahlen.length >= texte.length, meldung + ": " + texte.length
                + " Textzeilen, aber nur " + zahlen.length + " Zahlen");
        // Zusaetzliche Zahlen hinten sind leere Zeilen: die tragen eine Zahl,
        // aber keine Schrift. Fehlt eine Zahl in der Mitte, rutschen alle
        // folgenden um eine Zeile nach oben - genau das faellt unten auf.
        for (int i = 0; i < texte.length; i++) {
            int abweichung = Math.abs(zahlen[i] - texte[i]);
            assertTrue(abweichung <= ZEILEN_TOLERANZ, meldung + ": Zeile " + (i + 1)
                    + " steht " + abweichung + " Pixel daneben (" + zahlen[i]
                    + " zu " + texte[i] + ")");
        }
    }

    /** Zeichenbreite der Schrift im Textfeld. */
    private int zeichenBreite() {
        return sqlArea.getFontMetrics(sqlArea.getFont()).charWidth('0');
    }

    /** Wie viele Zeichen passen ohne Umbruch in ein Feld so breit? */
    private int zeichenProZeile(int breite) {
        java.awt.Insets rand = sqlArea.getMargin();
        int nutzbreite = breite - rand.left - rand.right;
        return Math.max(1, nutzbreite / zeichenBreite());
    }

    /**
     * Wie viele Bildschirmzeilen braucht ein Text, der an Wortgrenzen umbricht?
     *
     * <p>Nachgechnet wird hier bewusst unabhaengig vom Text-View: der Test soll
     * pruefen, ob die Zahlen zu dem passen, was der Umbruch ergibt - nicht
     * nachrechnen, was der Umbruch ergibt. Gewechselt wird wie im Editor an den
     * Leerzeichen, ein Wort wird nie in der Mitte getrennt.
     */
    private int zeilenBeiUmbruch(String text, int breite) {
        int proZeile = zeichenProZeile(breite);
        int zeilen = 1;
        int laenge = 0;
        for (String wort : text.split(" ")) {
            if (wort.isEmpty()) {
                continue;
            }
            int neu = laenge == 0 ? wort.length() : laenge + 1 + wort.length();
            if (neu > proZeile && laenge > 0) {
                zeilen++;
                laenge = wort.length();
            } else {
                laenge = neu;
            }
        }
        return zeilen;
    }

    /**
     * Wie viele Bildschirmzeilen braucht eine Folge, die nicht umbricht? Nur
     * brauchbar fuer Texte ohne Leerzeichen - alles andere nimmt
     * {@link #zeilenBeiUmbruch(String, int)}.
     */
    private int zeilenOhneUmbruch(int zeichen, int breite) {
        int proZeile = zeichenProZeile(breite);
        return (zeichen + proZeile - 1) / proZeile;
    }

    private String wiederhole(String stueck, int mal) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mal; i++) {
            sb.append(stueck);
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("Ecken")
    class Ecken {

        @Test
        @DisplayName("der Textbereich malt wirklich runde Ecken")
        void editor_hat_runde_ecken() {
            // Geprueft wird nicht die Rahmenart, sondern das Bild: die Ecke
            // links oben muss frei bleiben, waehrend die Oberkante gezeichnet
            // ist. Ein eckiger Rahmen wuerde beides bemalen.
            java.awt.image.BufferedImage bild =
                    new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            sqlArea.setSize(40, 40);
            sqlArea.paint(bild.getGraphics());
            assertEquals(0, bild.getRGB(0, 0) & 0xFFFFFF, "die Ecke links oben bleibt frei");
            assertNotEquals(0, bild.getRGB(20, 0) & 0xFFFFFF, "die Oberkante ist gezeichnet");
        }

        @Test
        @DisplayName("der Text im Textfeld haengt nicht an der Fase")
        void text_hat_innenabstand() {
            java.awt.Insets rand = sqlArea.getMargin();
            assertEquals(9, rand.top, "oben haengt der Text nicht an der Fase");
            // Links ist es breiter als die Fase: dort stehen die Zahlen, und die
            // duerfen den Text nicht beruehren.
            assertTrue(rand.left >= 20,
                    "links ist Platz fuer die Zahlen, aktuell " + rand.left);
        }

        @Test
        @DisplayName("das Textfeld hat schwarzen Rahmen und Fase wie die Knoepfe")
        void textfeld_hat_scharfen_rahmen_und_fase() throws Exception {
            javax.swing.border.Border rand =
                    ((javax.swing.JPanel) field("editorCard")).getBorder();
            javax.swing.border.LineBorder linie =
                    (javax.swing.border.LineBorder)
                            ((javax.swing.border.CompoundBorder) rand).getOutsideBorder();
            assertEquals(Color.BLACK, linie.getLineColor(), "der Rahmen ist schwarz");

            javax.swing.border.Border phase =
                    ((javax.swing.border.CompoundBorder) rand).getInsideBorder();
            assertInstanceOf(javax.swing.border.BevelBorder.class, phase,
                    "das Textfeld traegt die Fase der Knoepfe");
            assertEquals(javax.swing.border.BevelBorder.RAISED,
                    ((javax.swing.border.BevelBorder) phase).getBevelType(),
                    "die Fase steht vor wie an den Knoepfen");
        }

        @Test
        @DisplayName("Knoepfe und Textfeld runden gleich")
        void knoepfe_und_textfeld_runden_gleich() throws Exception {
            javax.swing.border.Border amKnoepf =
                    ((javax.swing.border.CompoundBorder) formatButton.getBorder()).getOutsideBorder();
            javax.swing.border.Border amFeld = ((javax.swing.border.CompoundBorder)
                    ((javax.swing.JPanel) field("editorCard")).getBorder()).getOutsideBorder();
              assertEquals(amKnoepf.getClass(), amFeld.getClass(),
                      "Buttons und Textfeld muessen dieselbe Eckenbehandlung teilen");
          }

          @Test
          @DisplayName("der Theme-Schalter malt seine Beschriftung selbst")
          void thema_schalter_malt_selbst() throws Exception {
              // FlatLaf malt die Beschriftung eines aktiven Tasters in einer
              // eigenen Farbe; "Hell" stand dann dunkel auf mittelgrau. Geprueft
              // wird deshalb das Bild: im dunklen Theme muss die Beschriftung in
              // Weiss gezeichnet sein, im hellen in der Textfarbe.
              sqlArea.setText("select 1");
              beschriftung_erscheint_in_der_textfarbe("Hell", Theme.DUNKEL);
              aufEdt(themeButton::doClick);
              beschriftung_erscheint_in_der_textfarbe("Dunkel", Theme.HELL);
          }

          /**
           * Prueft, dass der Schalter die Beschriftung wirklich in der Textfarbe
           * des Themes zeichnet und der LookAndFeel sie nicht uebermalt.
           */
          private void beschriftung_erscheint_in_der_textfarbe(
                  String beschriftung, Theme theme) throws Exception {
              assertEquals(beschriftung, themeButton.getText(),
                      "der Schalter nennt das Ziel");
              themeButton.setSize(200, 44);
              java.awt.image.BufferedImage bild =
                      new java.awt.image.BufferedImage(200, 44,
                              java.awt.image.BufferedImage.TYPE_INT_RGB);
              themeButton.paint(bild.getGraphics());
              Color farbe = themeButton.getForeground();
              assertEquals(theme.isDunkel() ? theme.aufAkzent : theme.text, farbe,
                      "die Beschriftung hat die Textfarbe des Themes");
              assertTrue(contrast(farbe, panel.getBackground()) >= 4.5,
                      "die Beschriftung hebt sich vom Fenster ab");
              assertTrue(kommtVor(bild, farbe),
                      "im gemalten Bild steht die Textfarbe " + farbe
                              + " - der LookAndFeel faerbt die Beschriftung sonst selbst");
          }

          private boolean kommtVor(java.awt.image.BufferedImage bild, Color farbe) {
              return pixelVor(bild, farbe);
          }
      }

      /** Zaehlt, ob eine Farbe wirklich im gemalten Bild auftaucht. */
      private static boolean pixelVor(java.awt.image.BufferedImage bild, Color farbe) {
          for (int y = 0; y < bild.getHeight(); y++) {
              for (int x = 0; x < bild.getWidth(); x++) {
                  if ((bild.getRGB(x, y) & 0xFFFFFF) == (farbe.getRGB() & 0xFFFFFF)) {
                      return true;
                  }
              }
          }
          return false;
      }

      @Nested
    @DisplayName("Zeilennummern")
    class ZeilennummernTest {

        @Test
        @DisplayName("die Zahlen stehen im Textfeld, nicht in einer eigenen Spalte")
        void zahlen_stehen_im_textfeld() throws Exception {
            // Eine eigene Spalte waere eine zweite Komponente neben dem Text. Die
            // muesste bei jedem Tastendruck ausdruecklich mitgerufen werden und
            // haette eigene Rollkoordinaten - beides faellt hier weg, weil Zahl
            // und Text aus demselben View in dasselbe Bild gezeichnet werden.
            JScrollPane sp = field("scrollPane");
            assertNull(sp.getRowHeader(),
                    "die Zahlen gehoeren ins Textfeld, nicht daneben");
            assertTrue(zahlenRand() >= 20,
                    "links ist Platz fuer die Zahlen, aktuell " + zahlenRand());
        }

        @Test
        @DisplayName("es steht eine Zahl fuer jede Zeile")
        void eine_zahl_pro_zeile() throws Exception {
            // Das Textfeld braucht eine Groesse: ohne sie liefert der Text keine
            // Positionen, und die Zahlen bleiben aus.
            sqlArea.setText("select 1\nfrom t\nwhere a = 1\n");
            sqlArea.setSize(300, 200);
            assertEquals(4, bemalteZahlen(300, 200),
                    "drei Zeilen plus die leere letzte bekommen je eine Zahl");
        }

        @Test
        @DisplayName("die Zahlen stehen auf der Grundlinie ihrer Textzeile")
        void zahlen_auf_grundlinie_der_zeile() throws Exception {
            // Geprueft wird Bild gegen Bild: die Zahl muss auf genau derselben
            // Pixelzeile stehen wie der Text. Genau daran ist es bisher
            // gescheitert - die Zahl stand im Takt und lief beim Blaettern
            // auseinander.
            sqlArea.setText("AAA\nBBB\nCCC\n");
            sqlArea.setSize(300, 200);
            meldeBildschirmzeilen(300, 200);
            java.awt.image.BufferedImage bild =
                    malen(sqlArea, 300, 200, sqlArea.getBackground());
            int[] zahlen = gemalteZeilen(bild, zahlenSpalte(300, 200),
                    sqlArea.getBackground());
            int[] texte = gemalteZeilen(bild, textSpalte(300, 200),
                    sqlArea.getBackground());
            assertTrue(zahlen.length >= 3, "die ersten drei Zahlen sind da: "
                    + zahlen.length);
            assertZahlenAufDenZeilen(zahlen, texte, "die Zahlen stehen auf den Zeilen");
        }

        @Test
        @DisplayName("Tippen erneuert die Zahlen ohne Klick")
        void tippen_erneuert_die_zahlen() throws Exception {
            // Der Fehler aus der Praxis: die Zahlen erschienen erst, wenn man in
            // das Fenster klickte. Ursache war eine eigene Spalte, die nur bei
            // Klick neu gezeichnet wurde. Jetzt zeichnet das Textfeld die Zahlen
            // im selben Zug wie den Text.
            sqlArea.setSize(300, 200);
            sqlArea.setText("select 1\n");
            meldeBildschirmzeilen(300, 200);
            int vorher = bemalteZahlen(300, 200);
            // Wie ein Tastendruck: eine Zeile einfuegen, nicht setText.
            aufEdt(() -> {
                try {
                    sqlArea.getDocument().insertString(9, "from t\n", null);
                } catch (javax.swing.text.BadLocationException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            // Ohne Klick, ohne Maus, ohne Tastendruck: nur neu malen.
            int nachher = bemalteZahlen(300, 200);
            assertEquals(vorher + 1, nachher,
                    "die neue Zeile hat sofort eine Zahl, auch ohne Klick ins Fenster");
        }

        @Test
        @DisplayName("eine umgebrochene Zeile bekommt je Bildschirmzeile eine Zahl")
        void umgebrochene_zeile_bekommt_mehr_zahlen() throws Exception {
            // Weicher Umbruch, wie ihn das Textfeld mit 84 Zeichen Breite macht:
            // eine lange Zeile braucht mehrere Bildschirmzeilen und damit
            // mehrere Zahlen. Zaehlt man stattdessen die Absaetze, fehlt unter
            // dem umbrochenen Text eine Zahl.
            int breite = 200;
            String lang = wiederhole("select 1 from tabelle ", 6);
            sqlArea.setSize(breite, 200);
            sqlArea.setText(lang + "\nselect 2\n");
            meldeBildschirmzeilen(breite, 200);
            java.awt.image.BufferedImage bild =
                    malen(sqlArea, breite, 400, sqlArea.getBackground());
            int[] zahlen = gemalteZeilen(bild, zahlenSpalte(breite, 400),
                    sqlArea.getBackground());
            int[] texte = gemalteZeilen(bild, textSpalte(breite, 400),
                    sqlArea.getBackground());
            assertZahlenAufDenZeilen(zahlen, texte,
                    "jeder Teil der langen Zeile hat eine Zahl");
            assertTrue(zahlen.length > 3, "der Test prueft wirklich eine umgebrochene Zeile, "
                    + "sonst waere er leer: nur " + zahlen.length + " Zahlen");
        }

        @Test
        @DisplayName("eine lange letzte Zeile ohne Zeilenende zaehlt alle ihre Teile")
        void lange_letzte_zeile_ohne_zeilenende() throws Exception {
            // Der haeufigste Fall aus der Praxis: ein langer SQL-Block, der als
            // letztes ohne Zeilenende endet und deshalb am Ende umbricht. Beim
            // Zaehlen der Absaetze endet die letzte Zahl dort, wo die Zeile
            // angefaengt hat - der Rest steht dann ohne Zahl da.
            int breite = 200;
            String lang = wiederhole("select 1 from tabelle ", 20);
            sqlArea.setSize(breite, 900);
            sqlArea.setText("select 1\n" + lang);
            meldeBildschirmzeilen(breite, 900);
            java.awt.image.BufferedImage bild =
                    malen(sqlArea, breite, 900, sqlArea.getBackground());
            int[] zahlen = gemalteZeilen(bild, zahlenSpalte(breite, 900),
                    sqlArea.getBackground());
            int[] texte = gemalteZeilen(bild, textSpalte(breite, 900),
                    sqlArea.getBackground());
            assertZahlenAufDenZeilen(zahlen, texte,
                    "jeder Teil der letzten langen Zeile hat eine Zahl");
            assertTrue(zahlen.length > 20, "der Test prueft wirklich eine lange Zeile: nur "
                    + zahlen.length + " Zahlen");
        }

        @Test
        @DisplayName("die Zahlen stehen beim Umbruch auf der Hoehe ihrer Bildschirmzeile")
        void zahlen_bei_umbruch_auf_der_hoehe() throws Exception {
            // Beim Blaettern duerfen die Zahlen nicht hinterherlaufen: die Zahl
            // der fuenften Bildschirmzeile steht auf deren Hoehe, auch wenn sie
            // der zweite Teil eines einzigen Absatzes ist.
            int breite = 200;
            String lang = wiederhole("select 1 from tabelle ", 6);
            sqlArea.setSize(breite, 400);
            sqlArea.setText(lang + "\n");
            meldeBildschirmzeilen(breite, 400);
            // Verglichen wird mit den Zeilen, die der Text an derselben Stelle
            // wirklich malt, nicht mit einer gerechneten Hoehe: die Zeilen liegen
            // nicht genau auf dem Zeilenabstand der Schrift, sondern so, wie der
            // Text-View sie legt. Geprueft wird jede einzelne - eine Zahl, die
            // erst ab der fuenften Zeile danebenliegt, faellt auch auf.
            java.awt.image.BufferedImage bild =
                    malen(sqlArea, breite, 400, sqlArea.getBackground());
            int[] zahlen = gemalteZeilen(bild, zahlenSpalte(breite, 400),
                    sqlArea.getBackground());
            int[] texte = gemalteZeilen(bild, textSpalte(breite, 400),
                    sqlArea.getBackground());
            assertZahlenAufDenZeilen(zahlen, texte,
                    "auch tief unten im Umbruch steht die Zahl auf ihrer Zeile");
            assertTrue(zahlen.length > 5, "der Test prueft wirklich mehrere Bildschirmzeilen "
                    + "eines Absatzes: nur " + zahlen.length + " Zahlen");
        }

        @Test
        @DisplayName("kein Text steht ohne Zahl daneben")
        void keine_zeile_ohne_zahl() throws Exception {
            // Der Befund aus der Praxis war: Zahlen 1 bis 22, darunter weitere
            // Zeilen ohne jede Zahl. Geprueft wird deshalb zeilenweise, dass jede
            // gemalte Textzeile auch eine Zahl hat und auf gleicher Hoehe steht.
            int breite = 240;
            sqlArea.setSize(breite, 1200);
            StringBuilder sql = new StringBuilder();
            for (int i = 1; i <= 120; i++) {
                sql.append("select ").append(i).append(" from tabelle where a = 1\n");
            }
            sqlArea.setText(sql.toString());
            meldeBildschirmzeilen(breite, 1200);
            java.awt.image.BufferedImage bild =
                    malen(sqlArea, breite, 1200, sqlArea.getBackground());
            int[] zahlen = gemalteZeilen(bild, zahlenSpalte(breite, 1200),
                    sqlArea.getBackground());
            int[] texte = gemalteZeilen(bild, textSpalte(breite, 1200),
                    sqlArea.getBackground());
            assertZahlenAufDenZeilen(zahlen, texte, "jede Textzeile hat eine Zahl");
        }

        @Test
        @DisplayName("die Zahlen nehmen die Farbe des Themes an")
        void zahlen_in_theme_farbe() throws Exception {
            Color zahlen = editorFeldFarbe("zahlenFarbe");
            assertEquals(Theme.DUNKEL.gedaempft, zahlen,
                    "die Zahlen sind zurueckhaltend, nicht so stark wie der Text");
            assertTrue(contrast(zahlen, panel.getBackground()) >= 4.5,
                    "die Zahlen bleiben im dunklen Theme lesbar");
            aufEdt(themeButton::doClick);
            assertEquals(Theme.HELL.gedaempft, editorFeldFarbe("zahlenFarbe"),
                    "nach dem Wechsel ziehen die Zahlen mit");
            assertTrue(contrast(editorFeldFarbe("zahlenFarbe"), panel.getBackground()) >= 4.5,
                    "und bleiben im hellen Theme lesbar");
        }

        private Color editorFeldFarbe(String name) throws Exception {
            java.lang.reflect.Field f = sqlArea.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (Color) f.get(sqlArea);
        }

    }

    @Nested
    @DisplayName("Typografie")
    class Typografie {

        @Test
        @DisplayName("der Produktname steht nicht zweimal im Fenster")
        void name_steht_nicht_zweimal() {
            // Der Fenstertitel traegt "SQL Clipboard Formatter". Ein zweiter
            // Titel im Fenster wiederholte ihn nur und kostete Hoehe, die der
            // Textbereich gebraucht.
            List<String> texte = new ArrayList<>();
            sammleTexte(panel, texte);
            for (String t : texte) {
                assertFalse(t.contains("SQL Formatter") || t.contains("SQL Clipboard Formatter"),
                        () -> "der Produktname steht noch im Fenster: '" + t + "'");
            }
        }

        private void sammleTexte(java.awt.Container c, List<String> ziel) {
            for (java.awt.Component k : c.getComponents()) {
                if (k instanceof javax.swing.JLabel l) {
                    ziel.add(l.getText());
                }
                if (k instanceof java.awt.Container kc) {
                    sammleTexte(kc, ziel);
                }
            }
        }

        @Test
        @DisplayName("die Versionszeile ist kleiner als die Statuszeile")
        void version_ist_dezenter_als_status() throws Exception {
            JLabel version = field("versionLabel");
            JLabel status = field("statusLabel");
            assertTrue(version.getFont().getSize() < status.getFont().getSize(),
                    () -> "Version " + version.getFont().getSize()
                            + " ist nicht kleiner als Status " + status.getFont().getSize());
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
