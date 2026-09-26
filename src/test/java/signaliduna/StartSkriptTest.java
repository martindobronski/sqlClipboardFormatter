package signaliduna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
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
        Ergebnis ergebnis = fuehreRoh(List.of("bash", "-n", "start.sh"));
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
        befehl.add("bash");
        befehl.add(skript.getPath());
        befehl.add("-Pruefen");
        befehl.addAll(argumente.stream().filter(a -> !a.equals("-Pruefen")).toList());
        return fuehreRoh(befehl);
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
