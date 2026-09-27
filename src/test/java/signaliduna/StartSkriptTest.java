package signaliduna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prueft start.sh, ohne es zu starten.
 *
 * <p>Der Pruefmodus macht das moeglich: er ermittelt Java und Maven, baut aber
 * nicht und startet nicht. Das ist Absicht - die Startskripte sind Shell, und
 * ein Fehler darin faellt sonst erst beim Ausfuehren auf. Genau das ist
 * passiert: auf bash 3.2, dem Standard von macOS, brach die Ausgabe eines
 * leeren Arrays unter "set -u" mit "unbound variable" ab, sobald das Skript
 * ohne Argumente lief. Im Quelltext ist das nicht zu sehen.
 */
@DisplayName("Startskript")
class StartSkriptTest {

    private static File skript;

    @BeforeAll
    static void skriptFinden() {
        skript = new File("start.sh");
    }

    @Test
    @DisplayName("start.sh laeuft ohne Argumente durch")
    void ohne_argumente() {
        Ergebnis ergebnis = fuehreAus(List.of());
        assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
        assertTrue(ergebnis.output.contains("Java    :"), () -> "Ausgabe: " + ergebnis.output);
    }

    @Test
    @DisplayName("start.sh laeuft mit -Neu durch")
    void mit_neu() {
        Ergebnis ergebnis = fuehreAus(List.of("-Neu"));
        assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
        assertTrue(ergebnis.output.contains("Bauen   : ja"),
                () -> "-Neu muss einen Build erzwingen: " + ergebnis.output);
    }

    @Test
    @DisplayName("Argumente mit Leerzeichen bleiben ein Argument")
    void argumente_mit_leerzeichen() {
        // Genau die Stelle, die unter bash 3.2 mit set -u brach.
        Ergebnis ergebnis = fuehreAus(List.of("-Pruefen", "-Xmx512m", "-Duser.dir=/mit leer"));
        assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
        assertTrue(ergebnis.output.contains("Argumente: 2 an die JVM"),
                () -> "zwei JVM-Argumente erwartet: " + ergebnis.output);
    }

    @Test
    @DisplayName("die Skripte leiten den Jar-Namen von der pom.xml ab")
    void jar_name_kommt_aus_der_pom() throws Exception {
        // Fest eingetragen stand der Jar-Name in beiden Skripten - beim
        // Versionswechsel wäre er stillschweigend falsch gewesen und mitten im
        // Startvorgang als "unable to access jarfile" aufgetaucht.
        String pomVersion = versionAusPom();
        assertFalse(pomVersion.isBlank(), "keine Version in der pom.xml gefunden");

        Ergebnis pruefung = fuehreAus(List.of("-Pruefen"));
        assertTrue(pruefung.output.contains("target/SqlClipboardFormatter-" + pomVersion + ".jar"),
                () -> "der Pruefmodus nennt nicht das Jar der pom.xml: " + pruefung.output);

        for (String skript : List.of("start.sh", "start.bat")) {
            String inhalt = Files.readString(new File(skript).toPath(), StandardCharsets.ISO_8859_1);
            assertFalse(inhalt.matches("(?s).*SqlClipboardFormatter-\\d+\\.\\w+.*"),
                    skript + " enthaelt einen fest eingetragenen Jar-Namen");
        }
    }

    @Test
    @DisplayName("beide Skripte kennen dieselben Schalter")
    void schalter_der_sind_gleich() throws Exception {
        // start.sh kannte -Pruefen, start.bat nicht. Wer auf Windows danach
        // greift, landet bei Java in "Unrecognized option: -Pruefen" - das
        // sieht nach einem Skriptfehler aus und ist keiner.
        for (String skript : List.of("start.sh", "start.bat")) {
            String inhalt = Files.readString(new File(skript).toPath(), StandardCharsets.ISO_8859_1);
            for (String schalter : List.of("-Neu", "-Pruefen")) {
                // Nicht nur "kommt irgendwo vor": der Schalter muss an einem
                // Vergleich haengen. Ein reiner Kommentar-Treffer liess den Test
                // gruen, nachdem die Zeile entfernt war - der Test waere dann
                // genau fuer den Fehler blind gewesen, den er verhindern soll.
                Pattern vergleich =
                        java.util.regex.Pattern.compile("=\\s*\"" + Pattern.quote(schalter));
                assertTrue(vergleich.matcher(inhalt).find(),
                        skript + " vergleicht kein Argument mit " + schalter);
            }
        }
    }

    @Test
    @DisplayName("start.bat enthaelt kein unescapetes Pipezeichen in for /f")
    void startBat_ohne_pipe_in_for_f() throws Exception {
        // Innerhalb von for /f muss jedes | als ^| escaped werden. Ein
        // Pipebruch in einer Zeilenfortsetzung beendet cmd.exe ohne Meldung -
        // das Skript haette an dieser Stelle zwei eigene Regeln verletzt.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        boolean inForF = false;
        for (int i = 0; i < zeilen.size(); i++) {
            String zeile = zeilen.get(i);
            if (zeile.trim().isEmpty() || zeile.trim().toLowerCase().startsWith("rem")) {
                continue;
            }
            if (zeile.contains("for /f")) {
                inForF = true;
            }
            if (inForF && zeile.contains("|") && !zeile.contains("^|")) {
                // Pipes innerhalb einer if-Zeile sind harmlos, hier aber
                // genau die Stelle, an der for /f sie als Kommando trennt.
                final String nummer = String.valueOf(i + 1);
                final String fund = zeile.strip();
                assertTrue(false, () -> "start.bat Zeile " + nummer
                        + " hat ein unescapetes | in for /f: " + fund);
            }
            if (inForF && zeile.strip().endsWith(")") && !zeile.contains("for /f")) {
                inForF = false;
            }
        }
    }

    @Test
    @DisplayName("start.bat ruft Maven quotet auf, damit Leerzeichen im Pfad nicht brechen")
    void startBat_ruft_maven_quotet() throws Exception {
        // Maven liegt haeufig unter "C:\Program Files\...". Unquotet bricht
        // der Pfad dort auseinander und der Build scheitert ohne klare Meldung.
        // Der Aufruf muss also call "%MVN%" sein, nicht %MVN%.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        boolean aufgerufen = false;
        for (String zeile : zeilen) {
            String text = zeile.strip();
            if (text.isEmpty() || text.toLowerCase().startsWith("rem")) {
                continue;
            }
            if (text.contains("%MVN%") && !text.contains("if ")) {
                // Jede Verwendung ausserhalb einer Bedingung ist ein Aufruf.
                assertTrue(text.startsWith("call \"%MVN%\""),
                        () -> "Maven-Aufruf muss call \"%MVN%\" sein, ist aber: " + text);
                aufgerufen = true;
            }
            // "call" darf nicht im Pfad selbst stecken: quotet waere
            // "%MVN%" dann ein Programm namens "call mvnw.cmd".
            assertFalse(text.contains("set \"MVN=call"),
                    () -> "MVN enthaelt ein call und ist damit nicht quotbar: " + text);
        }
        assertTrue(aufgerufen, "kein Maven-Aufruf im Skript gefunden");
    }

    @Test
    @DisplayName("start.bat prueft MAVEN_HOME nur, wenn es gesetzt ist")
    void startBat_prueft_maven_home_nur_wenn_gesetzt() throws Exception {
        // Ohne "if defined" expandiert "%MAVEN_HOME%\bin\mvn.cmd" zu
        // "\bin\mvn.cmd" und der Test trifft die Datei im Wurzelverzeichnis
        // des Laufwerks. Ein solcher Treffer haette nichts mit Maven zu tun.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        for (String zeile : zeilen) {
            if (zeile.contains("MAVEN_HOME") && !zeile.strip().startsWith("rem")) {
                assertTrue(zeile.contains("if defined MAVEN_HOME"),
                        () -> "MAVEN_HOME wird ohne Abfrage expandiert: " + zeile.strip());
            }
        }
    }

    private String versionAusPom() throws Exception {
        String pom = Files.readString(new File("pom.xml").toPath(), StandardCharsets.UTF_8);
        java.util.regex.Matcher treffer =
                java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(pom);
        assertTrue(treffer.find(), "keine <version> in der pom.xml gefunden");
        return treffer.group(1);
    }

    @Test
    @DisplayName("die Shell-Syntax ist gueltig")
    void syntax() throws Exception {
        Ergebnis ergebnis = fuehreRoh(List.of(bash(), "-n", "start.sh"));
        assertEquals(0, ergebnis.exitcode, () -> "Syntaxfehler: " + ergebnis.output);
    }

    @Test
    @DisplayName("start.bat ist ASCII mit CRLF und Windows-Zeilenenden")
    void startBat_windows_konventionen() throws Exception {
        File bat = new File("start.bat");
        assertTrue(bat.isFile(), "start.bat fehlt");
        byte[] roh = java.nio.file.Files.readAllBytes(bat.toPath());
        for (byte b : roh) {
            // CR und LF sind erlaubt, alles andere muss druckbares ASCII sein.
            // Umlaute wuerde cmd.exe je nach Codepage zerlegen.
            if (b != '\r' && b != '\n') {
                assertTrue(b >= 0x20, "Umlaut, Tab oder Steuerzeichen in start.bat: Byte " + b);
            }
        }
        int zeilenumbrueche = 0;
        for (int i = 0; i < roh.length; i++) {
            if (roh[i] == '\n') {
                zeilenumbrueche++;
                assertTrue(i > 0 && roh[i - 1] == '\r',
                        "Zeile " + zeilenumbrueche + " ohne CR - cmd.exe ist das nicht gewohnt");
            }
        }
        assertTrue(zeilenumbrueche > 20, "start.bat sieht unvollstaendig aus");
    }

    // --- Hilfen -------------------------------------------------------------

    private record Ergebnis(int exitcode, String output) {
    }

    private Ergebnis fuehreAus(List<String> argumente) {
        List<String> befehl = new ArrayList<>();
        befehl.add(bash());
        befehl.add(skript.getPath());
        befehl.add("-Pruefen");
        befehl.addAll(argumente.stream().filter(a -> !a.equals("-Pruefen")).toList());
        return fuehreRoh(befehl);
    }

    /**
     * Git Bash, unter Windows ueber den vollen Pfad.
     *
     * <p>Der Name allein genuegt dort nicht: Windows sucht bei CreateProcess
     * zuerst in System32, und dort liegt {@code C:\Windows\System32\bash.exe} -
     * der Starter von WSL, nicht von Git. Der aus dem PATH aufgeloeste Befehl
     * war deshalb WSL, und der Test scheiterte mit der Meldung "Windows
     * Subsystem for Linux has no installed distributions", ohne je etwas ueber
     * start.sh zu sagen. Ein Eintrag im PATH hilft nicht, weil System32 Vorrang
     * hat. Deshalb wird der absolute Pfad gesucht.
     */
    @Test
    @DisplayName("eine mitgelieferte Laufzeit in jre/ gewinnt gegen System-Java")
    void jre_hat_vorrang() throws Exception {
        // Das ist kein Textvergleich, sondern ein echter Lauf: in jre/ liegt
        // ein Skript, das das Java weiterreicht, mit dem die Tests gerade laufen.
        // Erwartet wird, dass start.sh dieses zuerst nimmt und das als Quelle
        // nennt. Ohne die Ausgabe im Pruefmodus waere nicht sichtbar, welches
        // Java benutzt wurde.
        legeJreAn(true);
        try {
            Ergebnis ergebnis = fuehreAus(List.of());
            assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("(mitgeliefert)"),
                    () -> "jre/ wurde nicht bevorzugt: " + ergebnis.output);
        } finally {
            entferneJre();
        }
    }

    @Test
    @DisplayName("eine kaputte Laufzeit in jre/ wird uebersprungen, nicht beachtet")
    void kaputte_jre_wird_uebersprungen() throws Exception {
        // Der haeufigste Fall: ein halb entpackter Download. Die Datei da ist,
        // laeuft aber nicht. Das Skript darf daran nicht scheitern, es muss auf
        // JAVA_HOME zurueckfallen - sonst faellt der Start genau dann aus, wenn
        // ein zweites Java installiert ist und es gar nicht gebraucht wird.
        legeJreAn(false);
        try {
            Ergebnis ergebnis = fuehreAus(List.of());
            assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
            assertFalse(ergebnis.output.contains("(mitgeliefert)"),
                    () -> "eine tote jre/ wurde als benutzt gemeldet: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("Java    :"),
                    () -> "es wurde gar kein Java gefunden: " + ergebnis.output);
        } finally {
            entferneJre();
        }
    }

    // --- start.local.conf ----------------------------------------------------
    //
    // Die folgenden Tests laufen start.sh wirklich, nicht nur ueber einen
    // Textvergleich. Fuer start.bat gibt es keinen ausfuehrbaren Weg hier, der
    // Schalter -Pruefen liefert dort die Quelle mit; der Windows-Nachweis fuer
    // start.bat steht deshalb in windows-pruefung.yml.

    @Test
    @DisplayName("start.local.conf setzt das Java, aus dem der Start kommt")
    void konfiguration_setzt_das_java() throws Exception {
        // Der Rueckfall ist der ganze Zweck der Datei: auf einem Rechner, auf
        // dem ein altes Java im PATH steht, nennt start.sh sonst das alte.
        String pfad = legeJavaShimAn("konfig-einfach");
        legeKonfigurationAn("java=" + pfad + "\n");
        try {
            Ergebnis ergebnis = fuehreAus(List.of());
            assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("(start.local.conf)"),
                    () -> "die Konfiguration wurde nicht genutzt: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("Konfig  : "),
                    () -> "der Pruefmodus sagt nicht, ob die Datei gelesen wurde: "
                            + ergebnis.output);
        } finally {
            entferneKonfiguration();
            entferneOrdner(new File("konfig-einfach"));
        }
    }

    @Test
    @DisplayName("start.local.conf erlaubt Leerzeichen im Pfad")
    void konfiguration_erlaubt_leerzeichen() throws Exception {
        // "C:\\Program Files\\..." ist der Normalfall, nicht die Ausnahme. Ein
        // Parser, der am ersten Leerzeichen abschneidet, liefert einen Pfad, den
        // es nicht gibt - und der Start faellt dann auf etwas Zurueck, das man
        // nicht mit dem Eintrag in der Datei verbindet. Der Shim-Ordner
        // heisst deshalb "mit leerzeichen".
        String pfad = legeJavaShimAn("mit leerzeichen");
        legeKonfigurationAn("java=" + pfad + "\n");
        try {
            Ergebnis ergebnis = fuehreAus(List.of());
            assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("(start.local.conf)"),
                    () -> "Pfad mit Leerzeichen wurde verworfen: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("mit leerzeichen/java"),
                    () -> "der Pfad kam abgeschnitten an: " + ergebnis.output);
        } finally {
            entferneKonfiguration();
            entferneOrdner(new File("mit leerzeichen"));
        }
    }

    @Test
    @DisplayName("start.local.conf ueberspringt Kommentare und kaputte Zeilen")
    void konfiguration_ueberspringt_kaputtes() throws Exception {
        // Alles, was beim Schreiben einer Konfigurationsdatei passieren kann:
        // ein Kommentar, der selbst ein = enthaelt, fuehrende Leerzeichen, ein
        // BOM aus dem Editor, eine Zeile ganz ohne =, ein Schluessel ganz ohne
        // Wert. Erwartet wird, dass nur die echte Zuweisung wirkt und nichts
        // davon den Start verhindert. Ein stilles Scheitern waere hier das
        // Schlimmste: die Datei waere da, der Eintrag nicht.
        String pfad = legeJavaShimAn("konfig-kaputt");
        legeKonfigurationAn(
                "# Kommentar mit java=" + pfad + " darf nichts bewirken\n"
                + "\n"
                + "   java   =   " + pfad + "   \n"
                + "\uFEFFMAVEN=/irgendwo/nicht\n"
                + "MUEHL ohne gleichzeichen\n"
                + "=\n"
                + "maven-jdk=\n"
                + "unbekannter-schluessel=egal\n");
        try {
            Ergebnis ergebnis = fuehreAus(List.of());
            assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("(start.local.conf)"),
                    () -> "der einzige gueltige Eintrag kam nicht durch: " + ergebnis.output);
        } finally {
            entferneKonfiguration();
            entferneOrdner(new File("konfig-kaputt"));
        }
    }

    @Test
    @DisplayName("ein Eintrag auf einen ungueltigen Pfad laesst den Start laufen")
    void ungueltiger_konfigurationspfad_bremst_nicht() throws Exception {
        // Der wichtigste Test fuer den Normalfall: ein Rechner, auf dem der
        // eingetragene Pfad nicht (mehr) existiert. Der Start darf daran
        // nicht scheitern - es gibt ja das Java im PATH, und das Skript kann
        // es selbst finden. Sonst macht ein falscher Eintrag die App
        // unstartbar, obwohl vorher alles lief.
        legeKonfigurationAn("java=/gibt/es/nicht/java\nmaven=/gibt/es/nicht/mvn.cmd\n");
        try {
            Ergebnis ergebnis = fuehreAus(List.of("-Pruefen"));
            assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
            assertFalse(ergebnis.output.contains("(start.local.conf)"),
                    () -> "ein toter Pfad wurde als benutzt gemeldet: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("Java    :"),
                    () -> "es wurde gar kein Java gefunden: " + ergebnis.output);
        } finally {
            entferneKonfiguration();
        }
    }

    @Test
    @DisplayName("jre/ gewinnt weiterhin gegen start.local.conf")
    void jre_gewinnt_gegen_die_konfiguration() throws Exception {
        // Die Zusage des Releases: die mitgelieferte Laufzeit ist die einzige,
        // von der wir wissen, dass sie zum Jar passt. Ein Eintrag in der
        // Konfiguration darf sie nicht ueberstimmen - sonst verliert das
        // Release die Eigenschaft, auf der es beruht, und zwar nur auf den
        // Rechnern, auf denen jemand einen Eintrag gemacht hat. Genau das waere
        // der denkbar schlechte Fall: eine Einschraenkung, die nur dort
        // auffaellt, wo jemand hilfreich sein wollte.
        legeJreAn(true);
        String pfad = legeJavaShimAn("konfig-neben-jre");
        legeKonfigurationAn("java=" + pfad + "\n");
        try {
            Ergebnis ergebnis = fuehreAus(List.of());
            assertEquals(0, ergebnis.exitcode, () -> "Abbruch: " + ergebnis.output);
            assertTrue(ergebnis.output.contains("(mitgeliefert)"),
                    () -> "jre/ wurde von der Konfiguration verdraengt: " + ergebnis.output);
            assertFalse(ergebnis.output.contains("(start.local.conf)"),
                    () -> "die Konfiguration kam vor jre/ zum Zug: " + ergebnis.output);
        } finally {
            entferneKonfiguration();
            entferneOrdner(new File("konfig-neben-jre"));
            entferneJre();
        }
    }

    @Test
    @DisplayName("start.bat setzt JAVA_HOME fuer Maven nur mit start.local.conf")
    void startbat_setzt_java_home_nur_bedingt() throws Exception {
        // Der Bug, den das alles hier ausgeloest hat: ein fest eingetragenes
        // "set JAVA_HOME=..." im Skript. Maven startet seine JVM ueber
        // JAVA_HOME und faellt sonst nicht auf den PATH zurueck - auf jedem
        // Rechner ohne genau dieses JDK war der Build damit kaputt, ohne dass
        // man etwas an der Konfiguration gesehen haette. Deshalb muss die
        // Zeile an einem Schalter haengen.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        boolean bedingt = false;
        for (String zeile : zeilen) {
            if (zeile.contains("set \"JAVA_HOME=") && !zeile.strip().startsWith("rem")) {
                assertTrue(zeile.contains("if defined CFG_MAVENJDK"),
                        () -> "JAVA_HOME wird ohne Bedingung gesetzt: " + zeile.strip());
                bedingt = true;
            }
        }
        assertTrue(bedingt, "start.bat traegt JAVA_HOME nicht mehr ueber start.local.conf");
    }

    @Test
    @DisplayName("start.bat und start.sh tragen keine fest eingetragenen Pfade")
    void keine_fest_eingetragenen_pfade() throws Exception {
        // Maschinenspezifische Pfade gehoeren in start.local.conf, nicht in
        // eine Datei, die an jeden Nutzer ausgeliefert wird. Geprueft wird
        // nicht nur auf einen bestimmten Pfad, sondern auf die Form: jeder Wert,
        // der in JAVA, JAVA_HOME, MVN oder CFG_* gesetzt wird, muss eine
        // Variable sein. Sonst rutscht der naechste feste Pfad unbemerkt
        // wieder herein.
        Pattern hart = Pattern.compile("set \"(JAVA|JAVA_HOME|MVN|CFG_[A-Z]+)=[A-Za-z]:",
                Pattern.CASE_INSENSITIVE);
        for (String skript : List.of("start.bat", "start.sh")) {
            List<String> zeilen = Files.readAllLines(new File(skript).toPath(),
                    StandardCharsets.ISO_8859_1);
            for (String zeile : zeilen) {
                String ohneKommentar = zeile.strip();
                if (ohneKommentar.startsWith("rem") || ohneKommentar.startsWith("#")) {
                    continue;
                }
                assertFalse(hart.matcher(ohneKommentar).find(),
                        () -> skript + " traegt einen fest eingetragenen Pfad: " + ohneKommentar);
            }
        }
    }

    @Test
    @DisplayName("beide Skripte lesen dieselben drei Schluessel")
    void beide_skripte_kennen_dieselben_schluessel() throws Exception {
        // Sonst haetten die beiden Startwege unterschiedliche Regeln, und die
        // Reihenfolge in der Anleitung waere fuer eines von beiden falsch.
        for (String skript : List.of("start.bat", "start.sh")) {
            String inhalt = Files.readString(new File(skript).toPath(),
                    StandardCharsets.ISO_8859_1);
            for (String schluessel : List.of("start.local.conf", "CFG_JAVA",
                    "CFG_MAVEN", "CFG_MAVENJDK")) {
                assertTrue(inhalt.contains(schluessel),
                        () -> skript + " kennt " + schluessel + " nicht");
            }
        }
    }

    @Test
    @DisplayName("start.bat liest die Konfiguration, bevor es Java sucht")
    void startbat_liest_die_konfiguration_vor_der_java_suche() throws Exception {
        // Der Sprung, der den ganzen Konfigurationsblock wertlos machte: der
        // Argument-Loop endet mit "goto javaSuchen", und dieses Label stand
        // hinter dem Block. Die Datei wurde also nie gelesen - bei jedem
        // Aufruf, auch ohne Argument. Im Quelltext ist so ein Sprung ueber
        // ausfuehrbare Zeilen nicht zu sehen, und der Start klappt hinterher
        // trotzdem, weil es ohne die Datei genauso laeuft: das ist ein Fehler,
        // der aussieht wie ein Rechner, der die Datei nicht braucht.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        Pattern ausstieg = Pattern.compile("^if \"%~1\"==\"\" goto :?(\\S+)$",
                Pattern.CASE_INSENSITIVE);
        int spring = -1;
        String gefunden = "";
        for (int i = 0; i < zeilen.size(); i++) {
            Matcher treffer = ausstieg.matcher(zeilen.get(i).strip());
            if (treffer.matches()) {
                spring = i;
                gefunden = treffer.group(1);
            }
        }
        final String ziel = gefunden;
        assertTrue(spring >= 0, "start.bat hat keine Argument-Schleife gefunden");
        int konfig = indexeVon(zeilen, z -> z.contains("set \"SQLFORMATTER_CONF="));
        assertTrue(konfig >= 0, "start.bat liest keine start.local.conf");
        int label = indexeVonLabel(zeilen, ziel);
        assertTrue(label >= 0, () -> "start.bat springt zu einem Label, das es nicht gibt: " + ziel);
        assertTrue(label < konfig, "start.bat springt ueber die Konfiguration hinweg: Zeile "
                + (label + 1) + " liegt vor dem Lesen in Zeile " + (konfig + 1)
                + " - start.local.conf wird nie gelesen");
    }

    @Test
    @DisplayName("jeder Sprung und jedes call in start.bat hat sein Label")
    void startbat_jeder_sprung_hat_sein_label() throws Exception {
        // Ein Tippfehler im Ziel springt ans Ende der Datei, und cmd.exe
        // beendet das Skript dann ohne eine einzige Meldung. Der Pruefmodus
        // sagt nichts mehr, der Start tut so, als gaebe es kein Java. Auf
        // macOS gibt es dafuer keinen Test: die Datei laeuft dort nie.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        Pattern sprung = Pattern.compile("^(?:goto|call) :?([A-Za-z][A-Za-z0-9]*)",
                Pattern.CASE_INSENSITIVE);
        for (String zeile : zeilen) {
            String ohneKommentar = zeile.strip();
            if (ohneKommentar.toLowerCase().startsWith("rem")) {
                continue;
            }
            Matcher treffer = sprung.matcher(ohneKommentar);
            if (!treffer.find()) {
                continue;
            }
            String ziel = treffer.group(1);
            assertTrue(indexeVonLabel(zeilen, ziel) >= 0,
                    () -> "start.bat springt zu einem Label, das es nicht gibt: " + ziel);
        }
    }

    @Test
    @DisplayName("die Vorlage start.conf.example ist unwirksam")
    void die_vorlage_ist_unwirksam() throws Exception {
        // Sie wird als Muster zum Kopieren verkauft. Waere in ihr auch nur eine
        // aktive Zuweisung, wuerde jemand sie ungeprueft benutzen und sein
        // Rechnerpfand wuerde den Weg in die Historie finden.
        List<String> zeilen = Files.readAllLines(new File("start.conf.example").toPath(),
                StandardCharsets.ISO_8859_1);
        Pattern zuweisung = Pattern.compile("^\\s*[A-Za-z][A-Za-z0-9-]*\\s*=");
        for (String zeile : zeilen) {
            String ohneKommentar = zeile.strip();
            if (ohneKommentar.startsWith("#")) {
                continue;
            }
            assertFalse(zuweisung.matcher(ohneKommentar).find(),
                    () -> "die Vorlage enthaelt eine wirksame Zeile: " + ohneKommentar);
        }
    }

    @Test
    @DisplayName("start.local.conf wird nicht mitversioniert")
    void lokale_konfiguration_ist_nicht_versioniert() throws Exception {
        // Die Kopie traegt die Pfade eines Rechners. Die Vorlage dagegen ist
        // eingecheckt - ohne sie wuesste niemand, welche Schluessel es gibt.
        assertTrue(ignoriert("start.local.conf"),
                "start.local.conf wird nicht ignoriert - die Pfade eines Rechners"
                        + " landen in der Historie");
        assertFalse(ignoriert("start.conf.example"),
                "die Vorlage wird ignoriert - ohne sie kennt niemand die Schluessel");
        assertTrue(new File("start.conf.example").isFile(),
                "start.conf.example fehlt - ohne Vorlage ist die Datei nicht auffindbar");
    }

    @Test
    @DisplayName("start.bat zitiert den Pfad der mitgelieferten Laufzeit")
    void startbat_quotet_den_jre_pfad() throws Exception {
        // Derselbe Fehler wie bei Maven: "C:\Program Files\..." bricht an
        // Leerzeichen auseinander, sobald der Pfad unquotiert in den Aufruf
        // wandert. Static, weil sich das auf einem Mac nicht ausfuehren laesst.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        boolean gefunden = false;
        for (String zeile : zeilen) {
            if (zeile.contains("jre") && zeile.contains("java.exe") && !zeile.strip().startsWith("rem")) {
                assertTrue(zeile.contains("\"%~dp0jre\\bin\\java.exe\""),
                        () -> "jre-Pfad nicht gequotet: " + zeile.strip());
                gefunden = true;
            }
        }
        assertTrue(gefunden, "start.bat sucht die mitgelieferte Laufzeit nicht");
    }

    @Test
    @DisplayName("jre/ ist von der Versionsverwaltung ausgenommen")
    void jre_ist_nicht_versioniert() {
        // Rund 90 MB Binaerdateien, je nach Plattform verschieden. Sie duerfen
        // nicht in die Historie: jeder Clone muesste sie sonst mitziehen, fuer
        // immer, auf jeder Plattform.
        assertTrue(ignoriert("jre/bin/java"),
                "jre/ wird nicht ignoriert - die Laufzeit landet in der Historie");
    }

    @Test
    @DisplayName("start.bat lehnt nicht nach /dev/null um")
    void startbat_kein_dev_null() throws Exception {
        // ">/dev/null" ist die Umleitung aus der Unix-Welt. In cmd.exe legt sie
        // eine Datei an und verweigert bei fehlendem Verzeichnis den Dienst -
        // die ganze Zeile wird dann nicht ausgefuehrt, und der Zweig, in dem
        // sie steht, prueft nie etwas. Der Fehler ist nur sichtbar, wenn man
        // gerade kein JAVA_HOME gesetzt hat, deshalb ist er lange unentdeckt
        // geblieben: die Windows-CI setzt immer ein JAVA_HOME.
        List<String> zeilen = Files.readAllLines(new File("start.bat").toPath(),
                StandardCharsets.ISO_8859_1);
        for (String zeile : zeilen) {
            if (zeile.strip().startsWith("rem")) {
                continue;
            }
            assertFalse(zeile.contains("/dev/null"),
                    () -> "Unix-Umleitung in einer ausgefuehrten Zeile: " + zeile.strip());
        }
    }

    @Test
    @DisplayName("start.sh findet das Jar auch ohne pom.xml, wie im Release-ZIP")
    void startsh_kommt_ohne_pom_xml_klar() throws Exception {
        // Das Release-ZIP enthaelt kein pom.xml und kein src/ - nur start.sh,
        // jre/ und target/*.jar. Beide Skripte haben ihren Jar-Namen bisher
        // zwingend aus der pom.xml gelesen und dort abgebrochen. Genau daran
        // waere jedes entpackte Release unstartbar gewesen. Der Test baut das
        // Verzeichnis nach und fuehrt es wirklich aus.
        Path zip = Files.createTempDirectory("release-ohne-pom");
        try {
            Files.copy(Path.of("start.sh"), zip.resolve("start.sh"));
            Files.createDirectories(zip.resolve("target"));
            Path jar = zip.resolve("target/SqlClipboardFormatter-9.9.9-probe.jar");
            Files.writeString(jar, "Platzhalter, es zaehlt nur der Name\n", StandardCharsets.UTF_8);

            // start.sh braucht Maven und pom.xml nicht, um den Namen zu
            // finden - der Pruefmodus bricht vorher ab, falls doch.
            Process lauf = new ProcessBuilder(bash(), "-c",
                    "cd '" + zip + "' && ./start.sh -Pruefen")
                    .redirectErrorStream(true).start();
            String ausgabe = new String(lauf.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, lauf.waitFor(), () -> "Abbruch im Release-Verzeichnis: " + ausgabe);
            assertTrue(ausgabe.contains("target/SqlClipboardFormatter-9.9.9-probe.jar"),
                    () -> "das Jar wurde ohne pom.xml nicht gefunden: " + ausgabe);
        } finally {
            try (var dateien = Files.walk(zip)) {
                dateien.sorted(java.util.Comparator.reverseOrder()).forEach(d -> {
                    try {
                        Files.deleteIfExists(d);
                    } catch (Exception ignoriert) {
                    }
                });
            }
        }
    }

    /**
     * Legt {@code jre/bin/java} im Projektverzeichnis an.
     *
     * @param brauchbar wenn true ein Skript, das das laufende Java weiterreicht,
     *                  wenn false eine leere, nicht ausfuehrbare Datei
     */
    private void legeJreAn(boolean brauchbar) throws Exception {
        File bin = new File("jre/bin");
        assertTrue(bin.mkdirs() || bin.isDirectory(), "jre/bin liess sich nicht anlegen");
        File java = new File(bin, "java");
        if (brauchbar) {
            // Ein Skript statt eines Verweises: unter Windows erlaubt das Anlegen
            // eines Symbols einen, einem Verweis nicht ohne Developer-Modus.
            String echtesJava = System.getProperty("java.home") + File.separator + "bin"
                    + File.separator + "java";
            Files.writeString(java.toPath(),
                    "#!/bin/sh\nexec \"" + echtesJava + "\" \"$@\"\n", StandardCharsets.UTF_8);
        } else {
            Files.writeString(java.toPath(), "kein Java\n", StandardCharsets.UTF_8);
        }
        // Nur das Setzen pruefen. Das Entfernen gibt unter Windows false
        // zurueck - dort gibt es kein Ausfuehrungsrecht, das man wegnehmen
        // koennte. Genau daran ist der Test auf windows-latest gescheitert,
        // nicht an der Laufzeit.
        if (brauchbar) {
            assertTrue(java.setExecutable(true), "Ausfuehrbarkeit liess sich nicht setzen");
        }
    }

    /**
     * Legt ein ausfuehrbares {@code java} unter dem genannten Ordnernamen an.
     *
     * <p>Der Ordnername darf Leerzeichen enthalten - genau darum geht es bei
     * den Tests zur Konfiguration. Der Inhalt ist ein Skript statt eines
     * Verweises: unter Windows erlaubt das Anlegen eines Symbols einen, einem
     * Verweis nicht ohne Developer-Modus.
     *
     * @param ordnername Name des Ordners im Projektverzeichnis, mit Leerzeichen
     *                   erlaubt
     * @return der Pfad, wie er in {@code start.local.conf} eingetragen wird
     */
    private String legeJavaShimAn(String ordnername) throws Exception {
        File ordner = new File(ordnername);
        assertTrue(ordner.mkdirs() || ordner.isDirectory(),
                "Shim-Ordner liess sich nicht anlegen");
        File java = new File(ordner, "java");
        String echtesJava = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        Files.writeString(java.toPath(),
                "#!/bin/sh\nexec \"" + echtesJava + "\" \"$@\"\n", StandardCharsets.UTF_8);
        assertTrue(java.setExecutable(true), "Ausfuehrbarkeit liess sich nicht setzen");
        return "./" + ordnername + "/java";
    }

    /** Legt {@code start.local.conf} mit dem gegebenen Inhalt an.*/
    private void legeKonfigurationAn(String inhalt) throws Exception {
        Files.writeString(new File("start.local.conf").toPath(), inhalt,
                StandardCharsets.UTF_8);
    }

    /**
     * Raeumt {@code start.local.conf} wieder weg.
     *
     * <p>Die Datei steht in der .gitignore und ist damit schon harmlos, wenn
     * ein Test sie liegen laesst. Sie zu loeschen kostet nichts und haelt den
     * naechsten Test davon ab, einen Eintrag zu erben, den niemand gesetzt
     * hat.
     */
    private void entferneKonfiguration() {
        new File("start.local.conf").delete();
    }

    /**raeumt {@code jre/} wieder weg, damit ein Fehlschlag nichts zuruecklaesst.*/
    private void entferneJre() {
        File jre = new File("jre");
        File[] inhalt = jre.listFiles();
        if (inhalt != null) {
            for (File datei : inhalt) {
                if (datei.isDirectory()) {
                    entferneOrdner(datei);
                } else {
                    datei.delete();
                }
            }
        }
        jre.delete();
    }

    private void entferneOrdner(File ordner) {
        File[] inhalt = ordner.listFiles();
        if (inhalt != null) {
            for (File datei : inhalt) {
                if (datei.isDirectory()) {
                    entferneOrdner(datei);
                } else {
                    datei.delete();
                }
            }
        }
        ordner.delete();
    }

    /**
     * Beantwortet die Frage, ob Git den Pfad ignoriert - mit Git selbst.
     *
     * <p>Der Textvergleich in der Datei ist genau daran gescheitert: die Zeile
     * stand in der {@code .gitignore}, nur mit zwei Leerzeichen davor, und Git
     * las sie als ein anderes Muster. Der Test verglich {@code zeile.strip()}
     * und meldete „in Ordnung", waehrend der Ordner {@code jre/} mit seinen
     * 180 MB unversioniert auf der Platte lag. Eine Zeile zu lesen beweist
     * nichts ueber die Wirkung einer Zeile - also wird die Wirkung gefragt.
     *
     * <p>{@code --no-index} noetig: ohne das schweigt Git ueber Pfade, die
     * bereits versioniert sind, und der Test waere bei einem bereits
     * eingecheckten {@code start.conf.example} stillschweigend gruen.
     *
     * <p>Ohne Git im Arbeitsverzeichnis (etwa in einer ausgelieferten
     * Quellkopie) wird die Pruefung uebersprungen statt geraten.
     */
    private boolean ignoriert(String pfad) {
        Ergebnis ergebnis = fuehreRoh(List.of("git", "check-ignore", "-q", "--no-index", pfad));
        if (ergebnis.exitcode == -1 || ergebnis.exitcode == 128) {
            Assumptions.assumeTrue(false,
                    "git check-ignore lieferte keine Antwort (" + ergebnis.output
                            + ") - die Ignore-Regel ist so nicht pruefbar");
        }
        return ergebnis.exitcode == 0;
    }

    /** Index der ersten Zeile, auf die die Bedingung zutrifft, sonst -1.*/
    private static int indexeVon(List<String> zeilen,
            java.util.function.Predicate<String> bedingung) {
        for (int i = 0; i < zeilen.size(); i++) {
            if (bedingung.test(zeilen.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Index des Labels {@code :name}, sonst -1. Groß- und Kleinschreibung egal,
     *  weil cmd.exe sie nicht unterscheidet.*/
    private static int indexeVonLabel(List<String> zeilen, String name) {
        return indexeVon(zeilen, z -> z.strip().equalsIgnoreCase(":" + name));
    }

    private static String bash() {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("win")) {
            return "bash";
        }
        List<String> kandidaten = List.of(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                "C:\\Program Files\\Git\\usr\\bin\\bash.exe");
        for (String kandidat : kandidaten) {
            if (new File(kandidat).canExecute()) {
                return kandidat;
            }
        }
        // Kein Git Bash: lieber klar sagen, welches Skript fehlt, als in den
        // WSL-Starter zu laufen und dessen Meldung als Testergebnis zu lesen.
        throw new IllegalStateException(
                "Git Bash nicht gefunden. Auf windows-latest liegt es unter "
                        + "C:\\Program Files\\Git\\bin.");
    }

    private Ergebnis fuehreRoh(List<String> befehl) {
        try {
            ProcessBuilder pb = new ProcessBuilder(befehl);
            pb.directory(new File("."));
            Process p = pb.start();
            ByteArrayOutputStream gesammelt = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(gesammelt);
            }
            // stderr haengt manchen Aufrufern sonst ewig nach; die Ausgabe wird
            // bewusst nur von stdout gelesen, Fehler stehen in der Ausgabe.
            boolean beendet = p.waitFor(120, TimeUnit.SECONDS);
            if (!beendet) {
                p.destroyForcibly();
                return new Ergebnis(-1, "Zeitueberschreitung");
            }
            return new Ergebnis(p.exitValue(), gesammelt.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new Ergebnis(-1, e.toString());
        }
    }
}
