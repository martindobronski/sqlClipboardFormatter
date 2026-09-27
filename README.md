# SQL Clipboard Formatter

Ein kleines Swing-Werkzeug, das SQL aus der Zwischenablage einliest, formatiert
und zurückschreibt. Gedacht für den Fall, in dem man ein SQL-Fragment aus einem
Log, einem Chat oder der Datenbank-Konsole kopiert hat und es lesbar weitergeben
oder ausführen will.

- **Version:** 0.3
- **Java:** 17 oder neuer
- **Keine Laufzeit-Abhängigkeiten außer der JVM** — alle Bibliotheken sind ins Jar
  gepackt

**Wer das Programm benutzen will, statt es weiterzubauen, liest die
Anleitung für sein System:**

- **[Anleitung für macOS](docs/anleitung-macos.md)** — Terminal, Start per
  Doppelklick, Raycast-Hotkey
- **[Anleitung für Windows](docs/anleitung-windows.md)** — `start.bat`,
  Tastenkürzel, Fehlermeldungen und was sie bedeuten

Diese beiden Anleitungen richten sich an Nutzer ohne Programmierkenntnisse.
Alles Weitere auf dieser Seite richtet sich an Menschen, die am Programm
arbeiten.

---

## Inhalt

- [Schnellstart](#schnellstart)
- [Bedienung](#bedienung)
- [Wie formatiert wird](#wie-formatiert-wird)
- [Datenschutz und Sicherheit](#datenschutz-und-sicherheit)
- [Bauen](#bauen)
- [Startskripte](#startskripte)
- [Raycast-Hotkey](#raycast-hotkey)
- [Projektaufbau](#projektaufbau)
- [Versionierung](#versionierung)
- [Tests](#tests)
- [Bekannte Grenzen](#bekannte-grenzen)

---

## Schnellstart

```bash
git clone https://github.com/martindobronski/sqlClipboardFormatter.git
cd sqlClipboardFormatter
./start.sh
```

`start.sh` baut bei Bedarf und startet danach das gepackte Jar. Danach:

1. SQL in irgendeiner Anwendung markieren und kopieren
2. **Clipboard einlesen**
3. **SQL Formatieren**
4. **Ins Clipboard schreiben**

Auf Windows entsprechend `start.bat`. Java 17 muss installiert sein; Maven nur,
wenn das Jar noch nicht gebaut ist.

---

## Bedienung

| Element                          | Wirkung                                                                                                  |
| -------------------------------- | -------------------------------------------------------------------------------------------------------- |
| **Clipboard einlesen**           | Holt den Text aus der Zwischenablage ins Textfeld.                                                       |
| **SQL Formatieren**              | Formatiert den Text im Feld. Nur aktiv, wenn er nach SQL aussieht.                                       |
| **Ins Clipboard schreiben**      | Schreibt den Text aus dem Feld zurück. Nur aktiv, wenn er nach SQL aussieht.                             |
| **Dialect**                      | Legt fest, wie der Text interpretiert wird.                                                              |
| **Theme-Schalter** (oben rechts) | Wechselt zwischen dunkel und hell. Das Theme wird nicht gespeichert, die Anwendung startet immer hell. |
| **Beenden**                      | Schliesst das Fenster.                                                                                   |

### Zeilennummern zaehlen, was man sieht

Das Textfeld ist 84 Zeichen breit und bricht laengere Zeilen weich um. Die Zahlen
links zaehlen deshalb die **Bildschirmzeilen**, nicht die Absaetze: eine lange
Zeile, die auf zwei Bildschirmzeilen umbricht, bekommt zwei Zahlen. Beim
Blaettern passt damit jede Zahl zu der Zeile, die daneben steht — und die
Statuszeile rechts nennt dieselbe Zahl als "Zeilen".

Gezeichnet werden die Zahlen vom Textfeld selbst, in seinem linken Innenabstand
und im selben Zug wie der Text. Eine eigene Spalte daneben haette ihren eigenen
Blick auf den Umbruch gehabt: Sie zaehlte Absaetze, stand bei jeder Aenderung
einen Moment zu spaet und lief beim Blaettern auseinander.

Der Grund ist nicht die Schoenheit, sondern die Erreichbarkeit: Der Textbereich
waechst mit den Bildschirmzeilen, die der Text wirklich belegt. Zaehlt man
Absaetze, schneidet man eine umgebrochene Zeile ab, und ihr unterer Teil ist
weder zu sehen noch zu erreichen.

Statusmeldungen unten links verschwinden nach vier Sekunden von selbst. Warnungen
und Fehler bleiben stehen — eine Meldung über möglichen Datenverlust darf nicht
weglaufen, nur weil man kurz in eine andere Anwendung geschaut hat.

### Die Statuszeile ist eine Schranke, keine Dekoration

**Formatieren** und **Ins Clipboard schreiben** bleiben gesperrt, solange der Text
im Feld nicht als SQL erkannt wird. Erkannt wird am ersten sinntragenden Wort:

```
SELECT   INSERT   UPDATE   DELETE   MERGE   WITH   REPLACE   CREATE
ALTER    DROP     TRUNCATE GRANT    REVOKE  COMMENT  ...
```

Vorangehende Kommentare und Leerraum werden übersprungen. Fließtext wie
„Please select an option" fällt dadurch durch: `select` allein reicht nicht, es
muss das erste Wort sein.

Wer bewusst ein Fragment formatieren will, beginnt es mit einem passenden Wort —
`WHERE a = 1` funktioniert.

### Der Dialekt-Hinweis

Rechts neben der Auswahl steht, welche Dialekte für den aktuellen Text ein
**anderes** Ergebnis liefern. „Standard SQL genügt" heißt: die Auswahl kann
bleiben, wie sie ist. Bei Texten über 4 000 Zeichen wird nicht verglichen.

Der Hinweis ist eine Warnung, keine Korrektur — es wird nichts automatisch
umgestellt.

---

## Wie formatiert wird

Formatiert wird mit [sql-formatter](https://github.com/sql-formatter-org/sql-formatter).
Vier Leerzeichen Einrückung, Schlüsselwörter groß — bewusst so, dass der Wechsel
vom früheren, einfacheren Formatter nicht wie ein Stilbruch wirkt.

### Die sechs Dialekte

| Auswahl            | Kurzform     |
| ------------------ | ------------ |
| Standard SQL       | `Standard`   |
| PostgreSQL         | `PostgreSQL` |
| MySQL / MariaDB    | `MySQL`      |
| SQL Server (T-SQL) | `T-SQL`      |
| Oracle PL/SQL      | `PL/SQL`     |
| IBM DB2            | `DB2`        |

### Der Fallback, und wann er greift

sql-formatter ist ein **Whitespace**-Formatierer, kein Validator. Bei unbrauchbarem
Input wirft er keine Ausnahme, sondern formatiert ihn — und zerschlitzt dabei
zwei Konstrukte nachweislich:

- **`$$ … $$` bzw. `$tag$ … $tag$`** — außer mit der Dialektwahl PostgreSQL wird
  `$$` zu `$ $`, und der Funktionskörper wird als Code formatiert.
- **Typisierte Literale** — `DATE'2020-01-01'` wird zu `DATE '2020-01-01'`.
  Das Leerzeichen macht daraus ungültiges SQL. Gilt in allen Dialekten.

Trifft eines davon zu, übernimmt `SqlFormatService`. Der ist keine Vereinfachung,
sondern eine **Normalisierung**: er räumt Whitespace auf und schreibt
Schlüsselwörter groß, schiebt aber **nie** Klauseln um. Die Statuszeile sagt dann,
dass nur normalisiert wurde.

### Zeilenenden

Die Ausgabe behält die Zeilenenden der Eingabe. Eine CRLF-Datei aus Windows
kommt als CRLF zurück, keine LF-Datei wird zu CRLF. Bei gemischten Eingaben
gewinnt die Mehrheit. Ein einzelnes `\r` in einem Literal ist Text und wird nicht
angefasst.

### Warum die Sternchen-Vermehrung und andere Operator-Zählungen im Code stehen

Nach jeder Formatierung wird geprüft, ob die Anzahl der SQL-Operatoren im Ergebnis
genauso hoch ist wie in der Eingabe. Stimmt sie nicht, gilt das Ergebnis als
unbrauchbar und wird **nicht** ausgegeben.

Das ist eine Absicherung gegen das, was ein reiner Whitespace-Formatierer
tatsächlich anrichten kann: `count(*)` und `t.*` enthalten Operatorzeichen, und
eine falsche Verschmelzung von `*` zu `* / *` oder ein verschlucktes `*` wäre
still — die Abfrage ändert ihre Bedeutung, ohne dass man es beim Lesen bemerkt.

---

## Datenschutz und Sicherheit

Das Werkzeug arbeitet **ausschließlich lokal**. Es gibt keine Netzwerkanbindung,
keine Telemetrie, keine Konfigurationsdatei und keinen Hintergrunddienst.

Was das praktisch heißt:

- Der Text verlässt den Rechner nicht. Formatieren passiert im Speicher.
- **Die Zwischenablage wird nicht ungefragt überschrieben.** Erst
  *Ins Clipboard schreiben* schreibt, und solange das Feld Text enthält, der nicht
  als SQL erkannt wird, ist der Knopf gesperrt.
- Es wird nichts gespeichert: kein Verlauf, keine zuletzt geladenen Dateien, keine
  Fensterposition.

Nützlich ist deshalb: Kopiere SQL aus einer Datenbank-Konsole, und formatiere es
in einem Fenster, das du nebenbei offen hältst.

---

## Bauen

```bash
mvn clean verify
```

Ergebnis: `target/SqlClipboardFormatter-0.3.jar` — ein ausführbares Jar mit allem
drin.

Einzelne Schritte:

| Befehl             | Wirkung                                               |
| ------------------ | ----------------------------------------------------- |
| `mvn compile`      | Kompilieren                                           |
| `mvn test`         | Tests ausführen                                       |
| `mvn package`      | Jar bauen                                             |
| `mvn clean verify` | Alles, wie oben — das, was vor dem Push laufen sollte |

Direkt starten ohne Skript:

```bash
java -jar target/SqlClipboardFormatter-0.3.jar
```

### Maven ohne Internet

```bash
mvn -o clean verify
```

Der Schalter `-o` erzwingt den Offline-Modus. Nützlich, wenn keine
Artifact-Server erreichbar sind. Ein Nachteil: nicht gecachte Plugins fehlen
dann auch. `mvn -o dependency:list` funktioniert in diesem Projekt zum Beispiel
nicht, `mvn -o clean verify` dagegen schon — die für den Build nötigen Plugins
liegen im lokalen Repository.

---

## Startskripte

### start.sh (macOS, Linux)

```bash
./start.sh              # starten, baut vorher bei Bedarf
./start.sh -Neu         # vorher neu bauen, auch wenn das Jar aktuell ist
./start.sh -Pruefen     # alles prüfen, weder bauen noch starten
./start.sh -Xmx512m     # Argument an die JVM durchreichen
```

Baut nur, wenn das Jar fehlt oder eine Quelldatei neuer ist als das Jar. Sonst
startet es sofort.

`-Pruefen` ist der Diagnosemodus — er beantwortet die Frage „ist bei mir
überhaupt alles vorhanden?", ohne ein Fenster aufzumachen:

```
$ ./start.sh -Pruefen
Java    : /usr/bin/java
Konfig  : keine
Maven   : mvn
Jar     : target/SqlClipboardFormatter-0.3.jar (vorhanden)
Bauen   : falls Quellen neuer
Argumente: 0 an die JVM
```

### start.bat (Windows)

```bat
start.bat              :: starten, baut vorher bei Bedarf
start.bat -Neu         :: vorher neu bauen
start.bat -Pruefen     :: alles prüfen, weder bauen noch starten
start.bat -Xmx512m     :: Argument an die JVM durchreichen
```

`-Pruefen` gibt dieselbe Auskunft wie unter macOS, damit sich ein Windows-Rechner
prüfen lässt, ohne ein Fenster zu öffnen:

```bat
> start.bat -Pruefen
Java    : C:\...\jre\bin\java.exe  (mitgeliefert)
Konfig  : keine
Maven   : mvn
Jar     : target\SqlClipboardFormatter-0.3.jar - vorhanden
Bauen   : falls Quellen neuer
Argumente: 0 an die JVM
```

Der Klammerzusatz nennt, **woher** das Java stammt. `Java : C:\...\java.exe` allein
lässt offen, ob das nun das mitgelieferte ist oder ein irgendwo installiertes — und
das ist genau die Frage, die man stellt, wenn etwas nicht startet.

### Feste Pfade: `start.local.conf`

Wer ein bestimmtes Java, ein bestimmtes Maven oder ein eigenes JDK für den Bau
durchsetzen will, schreibt es in eine Datei namens `start.local.conf` neben
`start.sh` bzw. `start.bat`. Sie wird **nicht** mitversioniert — die Pfade eines
Rechners gehören nicht in die Historie. Die Vorlage `start.conf.example` ist
eingecheckt und liegt dem Windows-ZIP bei:

```sh
cp start.conf.example start.local.conf
```

```
java=/usr/local/opt/openjdk/bin/java
maven=/usr/local/bin/mvn
maven-jdk=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
```

| Schlüssel   | Wofür                                                                                  |
| ----------- | -------------------------------------------------------------------------------------- |
| `java`      | die `java` (Windows: `java.exe`), mit der die App startet                              |
| `maven`     | die `mvn.cmd` bzw. `mvn`, mit der gebaut wird                                          |
| `maven-jdk` | das JDK, mit dem Maven übersetzt; leer lassen heißt `JAVA_HOME` bleibt unangetastet     |

Nach `#` ist alles Kommentar; Leerzeichen um den Wert sind erlaubt, Anführungszeichen
sind **nicht** nötig — `C:\Program Files\...` ist einfach eintragbar. Eine kaputte
Zeile wird übersprungen, nie zum Abbruch: aus einem Tippfehler darf kein „das Programm
startet nicht mehr" werden. Steht ein Pfad in der Datei, der nicht existiert, fällt
das Skript auf die normale Suche zurück — `Konfig  :` steht nur dann da, wenn der
Eintrag wirklich benutzt wurde, `(start.local.conf)` nur dann, wenn sein Java auch
läuft.

Der Unterschied zu `SQLFORMATTER_JAVA` ist die Dauer: die Variable gilt nur in dem
Fenster, in dem sie gesetzt ist, die Datei gilt für jeden Start in diesem Ordner.
Beide werden gelesen — siehe die Suchreihenfolge unten.

### Die mitgelieferte Laufzeit

Liegt im selben Ordner ein `jre/`, nimmt `start.bat` dessen Java. Es muss weder Java
noch Maven installiert sein:

```
sqlClipboardFormatter-0.3-windows-x64/
  start.bat
  start.conf.example        ← Vorlage für start.local.conf
  jre/bin/java.exe          ← wird zuerst genommen
  target/SqlClipboardFormatter-0.3.jar
  README.md
  docs/anleitung-windows.md
```

Die Reihenfolge der Suche ist: `jre/`, dann `start.local.conf`, dann die Variable
`SQLFORMATTER_JAVA`, dann `JAVA_HOME`, dann der `PATH`. Jeder Zweig prüft sein Java mit
`java -version` — ein halb entpackter Download darf den Start nicht verhindern, obwohl
ein zweites Java installiert ist. Die mitgelieferte Laufzeit gewinnt, weil sie die
einzige ist, von der wir wissen, dass sie zum Jar passt. Sie gewinnt auch gegen einen
Eintrag in `start.local.conf`: im entpackten Release soll die mitgelieferte Laufzeit
benutzt werden und nicht die eines Rechners, auf dem das ZIP nur herumliegt.

Wer die 56 MB nicht mitnehmen will, lässt den Ordner `jre/` weg und installiert Java
selbst; `start.bat` nimmt dann den Eintrag aus `start.local.conf`, `JAVA_HOME` oder den
`PATH`.

Zum Erzwingen einer bestimmten Laufzeit **nur für dieses Fenster**:

```bat
set SQLFORMATTER_JAVA=C:\Java\jdk-17\bin\java.exe
start.bat
```

Der Ordner `jre/` ist in `.gitignore` ausgenommen: rund 180 MB Binärdateien, je nach
Plattform verschieden, gehören nicht in die Historie, sondern in das Release-Asset.

Beide Skripte verstehen dieselben Schalter — das ist auch getestet, weil die
Gefahr sonst asymmetrisch bleibt: `-Pruefen` landet sonst bei Java und antwortet
mit `Unrecognized option: -Pruefen`, was nach einem Skriptfehler aussieht und
keiner ist.

### Wichtige Eigenschaften beider Skripte

- **Der Jar-Name wird aus der `pom.xml` gelesen**, nicht fest eingetragen. Nach
  einem Versionswechsel zeigen beide Skripte sonst ins Leere, und zwar erst dann,
  wenn schon alles andere läuft. Fehlt die `pom.xml` — wie im Release-ZIP —, übernimmt
  der Name des mitgelieferten Jars; ein Release lässt sich also auch aus einem Ordner
  starten, der keine Build-Dateien enthält.
- **Unter Windows hat `start.bat` CRLF-Zeilenenden** und ASCII-Inhalt. Das ist
  über `.gitattributes` sichergestellt: im Repository liegt die Datei mit LF, im
  ausgecheckten Arbeitsverzeichnis immer mit CRLF.
- **Fehlendes Java ist ein klarer Fehler**, kein stilles Scheitern. Unter macOS wird
  dafür nicht `which java` benutzt, sondern das Ergebnis von `java -version`
  geprüft — sonst trifft man das macOS-Stub unter `/usr/bin/java`, das nur
  „Java ist nicht installiert" ausgibt.
- **Ein toter Eintrag in `start.local.conf` verhindert den Start nicht.** Jeder
  Pfad wird mit `-version` geprüft, bevor er genommen wird; danach geht es die
  Kette weiter. Eine Datei, die falsch ist, kostet einen Blick in den Prüfmodus —
  nicht die Funktionsfähigkeit.

---

## Raycast-Hotkey

`raycast/start-sql-formatter.sh` macht den Formater über einen globalen Hotkey
erreichbar, ohne Raycast-Dateien im Projekt zu verstreuen. Raycast führt es
aus dem Kontext der **Raycast-App** aus — anders als ein Terminal-Skript.

Einrichtung:

1. `⌘` + `,` → **Script Commands** → **Add Script Directory**
2. Ordner `raycast` im Projekt wählen
3. `⌥` + `␣` → `SQL Formatter` → `⌘K` → **Configure Command** → **Record Hotkey**

Läuft bereits ein Fenster, holt das Skript es nur in den Vordergrund, statt ein
zweites zu öffnen — zwei Fenster würden beide dieselbe Zwischenablage
bearbeiten. **Das braucht die Berechtigung „Steuerungshilfen" für Raycast**, sonst
bleibt das Fenster hinten. Ohne die Berechtigung endet der Aufruf trotzdem ohne
Fehlermeldung.

Das Skript prüft beim Erkennen einer laufenden Instanz zusätzlich den
Prozessnamen. `pgrep -f` durchsucht die gesamte Kommandozeile und trifft damit
jedes `grep`, jeden Editor und jede Shell, in der der Name einmal vorkommt.

---

## Projektaufbau

```
pom.xml
start.sh, start.bat                 Startskripte
start.conf.example                  Vorlage für start.local.conf
raycast/                            Raycast-Skript
src/main/java/signaliduna/
  SqlClipboardFormatter.java        Fensterhülle, Einstiegspunkt
  FormatterPanel.java               Oberfläche, Bedienlogik, Versionszeile
  Theme.java                        Farben für dunkel und hell
  ClipboardService.java             Lesen und Schreiben der Zwischenablage
  SqlDetector.java                  Erkennt, ob ein Text mit SQL beginnt
  SqlDialect.java                   Die sechs Dialekte
  SqlPrettyFormatter.java           sql-formatter plus Sicherheitsprüfung
  SqlFormatService.java             Normalisierung als Fallback
  SqlSyntaxHighlighter.java         Einfärbung für das Textfeld
  SqlTextSpans.java                 Zerlegt Text in Literale, Bezeichner, Kommentare
src/main/resources/
  version.properties                Erzeugt aus der pom.xml
src/test/java/signaliduna/          252 Tests
```

Die Trennung ist Absicht: `SqlPrettyFormatter`, `SqlFormatService`,
`SqlDetector` und `ClipboardService` kennen kein Swing und sind ohne Fenster
testbar. `FormatterPanel` hält die Bedienlogik und das Layout.

### Abhängigkeiten

| Bibliothek                                                          | Version | Wofür                        |
| ------------------------------------------------------------------- | ------- | ---------------------------- |
| [sql-formatter](https://github.com/sql-formatter-org/sql-formatter) | 2.0.5   | Das Formatieren selbst       |
| [FlatLaf](https://github.com/JFormDesigner/FlatLaf)                 | 3.2.5   | Erscheinungsbild unter Swing |
| JUnit Jupiter                                                       | 5.10.2  | Nur zum Testen               |

---

## Versionierung

Die Versionsnummer steht **ausschließlich** in `pom.xml`. Alles andere leitet sich
daraus ab:

| Wer                      | Woher                                                                                                 |
| ------------------------ | ----------------------------------------------------------------------------------------------------- |
| `start.sh`, `start.bat`  | lesen die erste `<version>`-Zeile aus der pom.xml für den Jar-Namen; ohne pom.xml aus dem Jar-Namen |
| Versionszeile in der App | `version.properties`, von Maven aus der pom.xml erzeugt; im gepackten Jar zusätzlich aus dem Manifest |

Anheben heißt deshalb nur eines:

```bash
# Version in pom.xml aendern, dann
mvn clean package
```

An zwei Stellen stand die Nummer vorher fest — in einer Konstante und in beiden
Skripten. Beide wären beim Wechsel stillschweigend falsch geworden.

### Die Versionszeile

```
Version 0.3 vom 27.09.2026
```

Das Datum ist das **Build**-Datum aus dem Manifest, nicht das Datum des Releases.
Es ist fest formatiert (`dd.MM.yyyy`), damit die Anzeige in jedem System gleich
aussieht. Ohne gepacktes Jar — in der IDE oder in Tests — gibt es kein Manifest,
dann wird das heutige Datum genommen.

### Wichtig beim Übertragen auf andere Rechner

Wird nur `src/` kopiert, fehlt `version.properties` und die Zeile zeigt
„unbekannt". Das ist Absicht: eine erfundene Nummer wäre schlechter als eine
erkennbare Lücke. Die Datei entsteht beim Build.

---

## Tests

```bash
mvn test                       # alle
mvn test -Dtest=SqlPrettyFormatterTest   # eine Klasse
```

**252 Tests**, verteilt auf:

| Klasse                   | Tests | Wofür                                                           |
| ------------------------ | ----- | --------------------------------------------------------------- |
| `FormatterPanelTest`     | 97    | Layout, Themes, Zeilennummern, Toasts, Freischaltung, Reentranz   |
| `SqlPrettyFormatterTest` | 47    | Formatierregeln, Operatorerhalt, Zeilenenden                    |
| `SqlFormatServiceTest`   | 44    | Normalisierung, Literal- und Bezeichnerschutz                   |
| `SqlDetectorTest`        | 33    | SQL-Erkennung, parametrisiert über Start- und Nicht-Startwörter |
| `StartSkriptTest`        | 28    | Startskripte: Verhalten, Konventionen, Schalterparität, Laufzeitwahl |
| `ClipboardServiceTest`   | 3     | Zwischenablage lesen und schreiben                              |

Zum Nachzählen, weil die Zahl sonst leicht danebenliegt: `mvn test` meldet für
`FormatterPanelTest` in der Zusammenfassung `Tests run: 0`. Das ist kein Fehler,
sondern eine Eigenheit von `@Nested` — die Zähler sitzen in den inneren Klassen.
Wer nachzählen will, zählt die `<testcase>`-Elemente in
`target/surefire-reports/TEST-*.xml` oder liest die Summe aus der
Maven-Ausgabe (251).

`StartSkriptTest` führt `start.sh` wirklich aus — deshalb findet man dort Regressionen,
die man beim Lesen übersieht. Sechs Beispiele aus der Praxis:

- **Leeres Array unter `set -u`.** Auf bash 3.2, wie macOS es ausliefert, bricht
  die Expansion eines leeren Arrays ab. Startet man `start.sh` ohne Argumente, ist
  das Array leer — das Skript brach genau dann ab. Der Test deckt genau die Zeile
  ab, die später auch der `exec` benutzt.
- **Unescapetes `|` in `for /f`.** Innerhalb von `for /f` trennt ein Pipezeichen den
  Befehl; die Zeilenfortsetzung bricht ohne jede Meldung ab. Beide Startskripte
  sind auf genau das geprüft.
- **`>/dev/null` in `start.bat`.** Das ist die Umleitung aus der Unix-Welt. `cmd.exe`
  legt stattdessen eine Datei an und verweigert bei fehlendem Verzeichnis den Dienst —
  die ganze Zeile wird nie ausgeführt. Wer nur Java im `PATH` hatte, bekam so
  „kein Java gefunden", obwohl Java installiert war. Der Fehler ist lange
  unentdeckt geblieben, weil die Windows-CI immer ein `JAVA_HOME` setzt und der
  betroffene Zweig dadurch nie lief.
- **Jar-Name zwingend aus der `pom.xml`.** Beide Skripte brachen ohne sie ab, was
  jedes Release unstartbar gemacht hätte. Der Test baut die Verzeichnisstruktur des
  Release-ZIPs nach und führt sie aus.
- **`set -e` mit `pipefail` an der Versionszeile.** Fehlt die `pom.xml`, liefert `sed`
  Fehler 2, die Pipe gilt damit als fehlgeschlagen, und `set -e` beendet das Skript —
  ohne jede Ausgabe. Ein Absturz ohne Ausgabe sieht wie ein Skriptfehler aus statt
  wie ein falscher Pfad.
- **Feste Pfade in `start.bat`.** `set "JAVA_HOME=c:\dev\jdk\jdk-25.0.2+10"` hat jeden
  Rechner ohne genau dieses JDK kaputt gemacht — auch dort, wo Java und Maven
  vorhanden waren. Der Test sucht jetzt nach jeder Zeile, die einen Pfad statt
  einer Variablen setzt. Dazu der Gegenfall aus derselben Woche: eine
  `.gitignore`-Zeile mit zwei Leerzeichen davor ist für Git ein *anderes* Muster,
  und der Wächter, der nur die Zeile gelesen hat, meldete „in Ordnung". Er fragt
  Git jetzt selbst.

---

## Bekannte Grenzen

Ehrlich benannt, was nicht abgesichert ist:

- **`start.bat` läuft auf Windows, und das ist geprüft.** Ein Workflow führt es auf
  `windows-latest` aus: vollständige Testsuite, `start.bat -Pruefen` und ein echter
  Start, danach wird gefragt, ob eine JVM läuft. Dasselbe mit zwei Ergänzungen, die
  beide aus Fehlern entstanden sind: der Prüfmodus wird mit einer echten
  `start.local.conf` aufgerufen — brauchbarer Pfad mit Leerzeichen und toter Pfad —,
  und `start.bat -Neu` baut aus dem Zustand heraus, in dem es vorher kein Jar gab.
  Ein zweiter Job baut genau die Verzeichnisstruktur des Release-ZIPs nach —
  `start.bat`, `jre/`, das fertige Jar, ohne `pom.xml`, ohne `src/`, ohne Maven —,
  blendet danach `JAVA_HOME` und den `PATH` aus und verlangt, dass die gestartete JVM
  unterhalb von `jre/` läuft. Das ist der Nachweis, dass die App ohne System-Java
  startet, nicht nur die Anweisung, dass sie das könnte. Die Fehler, die dabei
  auffielen, sind in eigenen Tests festgehalten.
- **Ein gemeldeter Fehler mit verschwindenden `*` ist ungeklärt.** 47 Eingaben über
  alle sechs Dialekte sowie der Normalisierungs-Fallback ließen die Anzahl der
  Sternchen unverändert, ein Test über die laufende Anwendung ebenfalls. Die
  eingebaute Operatorprüfung fängt das heute ab, aber die ursprüngliche Ursache
  wurde nie gefunden — der Fehler könnte also in einer anderen Form wiederkehren.
- **Sitzungszustand wird nicht gespeichert.** Kein Theme, keine Fensterposition,
  kein Verlauf. Bewusst so, aber gut zu wissen.
- **Große Eingaben werden nicht auf Dialekte geprüft.** Über 4 000 Zeichen steht
  der Hinweis auf „—". Das Formatieren selbst ist nicht begrenzt.
- **Syntaxhervorhebung bis 100 000 Zeichen.** Darüber wird der Text nicht mehr
  eingefärbt, um die Bedienung flüssig zu halten.
