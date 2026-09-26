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
    void jre_ist_nicht_versioniert() throws Exception {
        // Rund 90 MB Binaerdateien, je nach Plattform verschieden. Sie duerfen
        // nicht in die Historie: jeder Clone muesste sie sonst mitziehen, fuer
        // immer, auf jeder Plattform.
        String gitignore = Files.readString(new File(".gitignore").toPath(), StandardCharsets.UTF_8);
        assertTrue(gitignore.lines().anyMatch(z -> z.strip().equals("jre/")),
                "jre/ steht nicht in der .gitignore");
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
