# Anleitung für Windows

Diese Anleitung richtet sich an alle, die den SQL Clipboard Formatter auf einem
Windows-Rechner benutzen wollen. Sie setzt keine Programmierkenntnisse voraus.

> **Sie suchen die Anleitung für einen Mac?** Dann nehmen Sie
> [anleitung-macos.md](anleitung-macos.md).

> ### Stand der Prüfung
>
> Diese Anleitung ist aus dem Skript `start.bat` herausgeschrieben und an jeder
> Stelle mit dem Quelltext abgeglichen. Sie ist außerdem **auf einem echten
> Windows-Rechner durchlaufen**: Ein automatischer Lauf auf `windows-latest`
> führt die vollständige Testsuite aus, ruft `start.bat -Pruefen` auf und startet
> das Programm wirklich — danach wird gefragt, ob eine JVM läuft. Ein zweiter
> Lauf baut genau die Verzeichnisstruktur auf, die Sie nach dem Entpacken des
> ZIPs vorfinden, blendet anschließend `JAVA_HOME` und den Suchpfad aus und
> verlangt, dass die gestartete JVM aus dem mitgelieferten Ordner `jre/` kommt.
>
> Das ist der Grund, warum Sie für den ersten Start weder Java noch Maven
> brauchen: Es ist nicht behauptet, sondern auf einem Rechner ohne Java
> nachgewiesen worden. Wenn bei Ihnen etwas abweicht, beginnen Sie mit
> Abschnitt 8; dort steht, wie Sie den Fehlertext zu Gesicht bekommen. Der
> erste sinnvolle Test ist immer `start.bat -Pruefen`.


---

## 1. Wofür ist das Programm?

Sie markieren SQL-Text in einem beliebigen Programm, kopieren ihn mit Strg+C in
die Zwischenablage und formatieren ihn mit einem Klick:

```
select a,b,c from tabelle where b=1 and c=2 order by a;
```

wird zu:

```sql
SELECT
    a,
    b,
    c
FROM
    tabelle
WHERE
    b = 1
    AND c = 2
ORDER BY
    a;
```

Das Ergebnis liegt danach wieder in der Zwischenablage, sodass Sie es mit
Strg+V an die gewünschte Stelle setzen können. Sie brauchen dafür **kein
Datenbankprogramm und keine Internetverbindung**.

## 2. Was Sie brauchen

Für den normalen Fall — Sie wollen das Programm benutzen — **brauchen Sie nichts
außer Windows.** Kein Java, kein Maven, keine Installation.

| Weg                                | Java | Maven | Internet beim Start |
| ---------------------------------- | ---- | ----- | ------------------ |
| **ZIP entpacken** (Abschnitt 3)    | —    | —     | nein               |
| Aus dem Quelltext (Abschnitt 4)    | 17+  | ja    | nein               |

Im ZIP liegt eine vollständige Java-Laufzeit bereits bei. Das ZIP selbst ist
rund 57 MB groß, entpackt sind es etwa 180 MB — fast alles die Laufzeit, die
Anwendung selbst liegt bei knapp 1 MB. Dafür startet das Programm auf einem
Rechner, auf dem noch nie Java war.

Wollen Sie die 57 MB nicht mitnehmen, lassen Sie den Ordner `jre/` nach dem
Entpacken einfach löschen und installieren Java selbst — dann nimmt `start.bat`
automatisch Ihr Java. Beides gleichzeitig ist unnötig; der Ordner `jre/` hat
Vorrang.

Auf einem Windows-Rechner mit ARM-Prozessor brauchen Sie die ARM-Version von
Java, nicht die für Intel. Ein ZIP gibt es derzeit nur für x64.

## 3. Der einfache Weg: ZIP entpacken und starten

1. Das ZIP herunterladen und entpacken. Dabei entsteht automatisch ein neuer
   Ordner `sqlClipboardFormatter-<Version>-windows-x64`; den bitte **nicht**
   wieder löschen, sonst fehlt dem Programm sein Zuhause.
2. In diesem Ordner Doppelklick auf `start.bat`.

Danach öffnet sich das Programm. Es läuft aus einem schwarzen Fenster, das
danach im Hintergrund bleibt — Sie schließen es nicht, solange Sie das Programm
benutzen. Das ist kein Fehler, sondern das Anzeigefenster der Laufzeit.

Sollte etwas nicht stimmen, prüfen Sie zuerst in einem PowerShell-Fenster im
selben Ordner:

```powershell
start.bat -Pruefen
```

Bei einem frisch entpackten ZIP sieht das so aus:

```
Java    : C:\...\jre\bin\java.exe  (mitgeliefert)
Maven   : nicht gefunden
Jar     : target\SqlClipboardFormatter-0.2.jar - vorhanden
Bauen   : falls Quellen neuer
Argumente: 0 an die JVM
```

Der Klammerzusatz sagt Ihnen, **woher** das Java stammt. Bei
`(mitgeliefert)` läuft alles auf der Laufzeit aus dem ZIP, und Sie können sich
sicher sein, dass keine zweite Java-Installation auf Ihrem Rechner stört.
Steht dort `(JAVA_HOME)` oder `(PATH)`, wurde der Ordner `jre/` nicht gefunden
und Ihr eigenes Java genommen — was auch in Ordnung ist, sofern es Java 17
oder neuer ist.

`Maven   : nicht gefunden` ist beim entpackten ZIP **richtig** und kein Fehler:
Maven wird nur gebraucht, um das Programm aus dem Quelltext zu übersetzen.

### Ein bestimmtes Java erzwingen

Falls auf Ihrem Rechner mehrere Java-Versionen liegen und Sie eine bestimmte
verwenden wollen, setzen Sie die Variable `SQLFORMATTER_JAVA` auf den Pfad zur
`java.exe`. Sie hat Vorrang vor `jre/`, `JAVA_HOME` und dem Suchpfad — damit
lässt sich ein unerwünschtes Java zuverlässig ausschließen:

```powershell
$env:SQLFORMATTER_JAVA = "C:\Program Files\Java\jdk-17\bin\java.exe"
start.bat
```

Gilt nur für dieses Fenster. Für dauerhaft:

```powershell
[Environment]::SetEnvironmentVariable("SQLFORMATTER_JAVA", "C:\Program Files\Java\jdk-17\bin\java.exe", "User")
```

## 4. Der andere Weg: aus dem Quelltext übersetzen

Dieser Weg ist nur nötig, wenn Sie das Programm selbst verändern wollen oder
wenn Sie ohne den Download auskommen. Er braucht **Java 17 oder neuer** und
**Maven**; beides können Sie vorher prüfen.

### Schritt 1: Java prüfen

Öffnen Sie PowerShell — etwa mit **Win** tippen, „PowerShell" eingeben,
Enter — und prüfen Sie:

```powershell
java -version
```

Sieht die Antwort so oder ähnlich aus, ist alles in Ordnung:

```
openjdk version "17.0.11" 2024-04-16
OpenJDK Runtime Environment (build 17.0.11+9)
```

Kommt stattdessen ein Fehler wie *Der Befehl "java" ist nicht
erkannt* oder *'java' is not recognized*, fehlt Java oder es ist zu alt.
Installieren Sie es über [Azul Zulu](https://azul.com/downloads/)
(„JDK", Windows, x64 oder ARM64).

### Schritt 2: Das Programm holen

Wechseln Sie in einen Ordner, in dem das Programm liegen darf. Eine
PowerShell öffnet zum Beispiel in Ihrem Benutzerordner, was passt. Dann:

```powershell
git clone https://github.com/martindobronski/sqlClipboardFormatter.git
cd sqlClipboardFormatter
```

### Erst prüfen, dann übersetzen

Bevor Sie zum ersten Mal starten, prüfen Sie die Umgebung. Das ist die schnellste
Art herauszufinden, woran es liegt:

```bat
start.bat -Pruefen
```

Sie sehen dann zum Beispiel:

```
Java    : C:\Program Files\Java\jdk-17\bin\java.exe
Maven   : mvn
Jar     : target\SqlClipboardFormatter-0.2.jar - fehlt
Bauen   : ja
Argumente: 0 an die JVM
```

Die Zeilen bedeuten:

| Zeile       | Bedeutung                                                                                                       |
| ----------- | --------------------------------------------------------------------------------------------------------------- |
| `Java`      | gefundenes Java. „Fehler: kein Java gefunden" heißt: zurück zu Schritt 1                                        |
| `Maven`     | gefundenes Maven oder „nicht gefunden". Fehlt es, ist das beim ersten Übersetzen ein Problem, später nicht mehr |
| `Jar`       | die übersetzte Programmdatei. „fehlt" ist beim ersten Mal normal                                                |
| `Bauen`     | ob beim Starten übersetzt werden muss                                                                           |
| `Argumente` | was an Java durchgereicht wird                                                                                  |

Steht bei `Jar` **fehlt**, übersetzen Sie einmal:

```powershell
.\start.bat -Neu
```

Das dauert beim ersten Mal etwa eine Minute, weil dabei Dateien nachgeladen
werden.

## 5. Schritt 3: Starten

Ab jetzt genügt ein Doppelklick auf **`start.bat`** im Explorer. Oder in der
PowerShell:

```powershell
.\start.bat
```

Das Skript übersetzt vorher automatisch neu, falls es nötig ist, und startet
danach das Programm.

> **Wichtig:** Das Programm läuft im selben schwarzen Fenster, aus dem Sie es
> gestartet haben — dieses Fenster bleibt offen und im Vordergrund, das
> Programmfenster liegt dahinter. Das ist beabsichtigt: Schließen Sie das
> schwarze Fenster, beendet sich auch das Programm. Beenden Sie das Programm
> also über dessen eigenes Fenster.

### Was die Schalter können

| Befehl               | Wirkung                                                  |
| -------------------- | -------------------------------------------------------- |
| `start.bat`          | starten, übersetzt vorher bei Bedarf neu                 |
| `start.bat -Neu`     | vorher neu übersetzen, auch wenn schon alles aktuell ist |
| `start.bat -Pruefen` | nur nachsehen, ob die Umgebung passt — startet nichts    |
| `start.bat -Xmx512m` | gibt 512 MB Speicher für das Programm frei               |

## 6. Bedienung

![Oberfläche im dunklen Design](bild-dunkel.png)

Oben steht der Titel **SQL Formatter**, rechts daneben ein Knopf zum Umschalten
zwischen dunkel und hell. Darunter das große Textfeld, rechts daneben die Auswahl
**Dialect:**. Ganz unten die Statuszeile.

### Der eine wichtige Punkt: die Statuszeile

**Die Statuszeile ist das Einzige, was Ihnen sagt, ob die Knöpfe etwas tun.**

| Inhalt des Feldes        | Statuszeile                                                                    | Knöpfe                   |
| ------------------------ | ------------------------------------------------------------------------------ | ------------------------ |
| leer                     | Bereit - Bitte SQL aus der Zwischenablage laden.                               | nur *Clipboard einlesen* |
| sieht nach SQL aus       | Bereit - Text sieht nach SQL aus.                                              | alle frei                |
| sieht nicht nach SQL aus | ⚠ Der Text sieht nicht nach SQL aus. Formatieren und Schreiben sind blockiert. | nur *Clipboard einlesen* |

Genau in diesem letzten Fall sind **SQL Formatieren** und **Ins Clipboard
schreiben** grau. Das ist Absicht: So überschreiben Sie nicht versehentlich einen
normalen Text aus Ihrer Zwischenablage mit SQL, das Sie gar nicht erwartet haben.
**Clipboard einlesen** bleibt immer anklickbar — Sie können also jederzeit nachsehen,
was in der Zwischenablage steht.

### Die vier Knöpfe

| Knopf                       | Wirkung                                                      |
| --------------------------- | ------------------------------------------------------------ |
| **Clipboard einlesen**      | holt den Text aus Ihrer Zwischenablage in das Feld           |
| **SQL Formatieren**         | formatiert den Text im Feld                                  |
| **Ins Clipboard schreiben** | legt den Text aus dem Feld in die Zwischenablage             |
| **Beenden**                 | beendet das Programm; steht rechts neben den anderen Knöpfen |

**Das sind zwei Schritte, nicht einer:** *SQL Formatieren* legt nichts in die
Zwischenablage. Erst *Ins Clipboard schreiben* tut das. So können Sie sich das
Ergebnis ansehen, bevor Sie es irgendwo einfügen.

### Normaler Arbeitsablauf

1. SQL in Ihrem Programm markieren und mit **Strg+C** kopieren
2. **Clipboard einlesen** → `✓ Text geladen. Formatieren und Schreiben sind freigeschaltet.`
3. **SQL Formatieren** → `✓ SQL-Text nach Best Practice formatiert.`
4. **Ins Clipboard schreiben** → `✓ SQL erfolgreich in die Zwischenablage kopiert.`
5. In Ihr Programm zurück, **Strg+V** einfügen

### Wenn die Formatierung nicht durchgreift

> ⚠ Nur normalisiert - Zeilenstruktur unverändert.

Dann erkennt das Programm Ihren Text zwar als SQL, Ihre gewählte Variante
liefert aber kein verlässliches Ergebnis — typischerweise MySQL-Syntax, während
Standard SQL eingestellt ist. Statt zu raten hat es den Text nur vorsichtig in
Ordnung gebracht und die Zeilenumbrüche unangetastet gelassen. Ein anderes
Ergebnis bekommen Sie in der Regel mit einer anderen Einstellung unter
**Dialect:**.

### Die Variante unter „Dialect:"

Für die allermeisten Fälle genügt **Standard SQL**. Wählen Sie eine andere
Variante, wenn Ihr Server eigene Syntax benutzt:

| Auswahl            | Für                                          |
| ------------------ | -------------------------------------------- |
| Standard SQL       | allgemein, passt für die meisten Datenbanken |
| PostgreSQL         | PostgreSQL                                   |
| MySQL / MariaDB    | MySQL, MariaDB                               |
| SQL Server (T-SQL) | Microsoft SQL Server                         |
| Oracle PL/SQL      | Oracle, einschließlich PL/SQL-Blöcken        |
| IBM DB2            | IBM DB2                                      |

Rechts neben der Auswahl steht, was die Wahl für den Text im Feld bewirkt:

| Anzeige                                       | Bedeutung                                                                                             |
| --------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `—`                                           | noch kein Text im Feld                                                                                |
| Standard SQL genügt                           | Ihre Ausstellung ist egal, alle Varianten kommen zum gleichen Ergebnis                                |
| wirksam: PostgreSQL, MySQL                    | mit diesen Varianten sieht das Ergebnis anders aus — hier lohnt die Wahl                              |
| Text zu lang, um die Dialekte zu vergleichen. | über 4000 Zeichen wird nicht verglichen. Das ist nur eine Anzeige, **das Formatieren läuft trotzdem** |

### Zeilenumbrüche: Windows bleibt Windows

Das Programm behält den Zeilenumbruch-Stil, den Ihr Text schon hatte. Kommt Ihr
SQL aus Windows-Programmen, bleiben die Umbrüche, wie sie im Editor stehen:
`CRLF` — ein Wagenrücklauf gefolgt von einem Zeilenvorschub, dargestellt als
`0D 0A`.

Dasselbe gilt für den Text, den das Programm zurückschreibt: Es wandelt nichts
um, nur weil es bequemer wäre.

So prüfen Sie, was in Ihrer Datei wirklich steht:

| Editor                 | Wo Sie es sehen                                                                        |
| ---------------------- | -------------------------------------------------------------------------------------- |
| **Notepad++**          | *Ansicht → Symbol anzeigen → Zeilenende anzeigen*                                      |
| **Visual Studio Code** | die Anzeige `CRLF` oder `LF` unten in der Statusleiste, nach einem Klick auf die Datei |

### Hell oder dunkel

Der Knopf oben rechts zeigt an, **wohin** Sie wechseln, nicht wo Sie sind: Im
dunklen Design steht dort `☀ Hell`, im hellen `☾ Dunkel`. Ein Klick schaltet um.
Die Wahl gilt für diese Sitzung; beim nächsten Start ist es wieder dunkel.

## 7. Optional: Schnellzugriff über eine Tastenkombination

Wenn Sie den Formatter oft brauchen, können Sie den Start an eine Taste binden:

1. Im Explorer mit der rechten Maustaste auf `start.bat` klicken
2. **Eigenschaften** öffnen
3. Unter **Kurzbefehl** in das Feld „Verknüpfung" eine Tastenkombination eintippen,
   etwa `Strg+Alt+S`
4. Mit **Übernehmen** schließen

Ab sofort startet die Kombination das Programm.

> Der erste Start hängt davon ab, wie schnell Ihr Rechner ist — beim ersten Mal
> wird übersetzt, das dauert einen Moment. Danach geht es schnell.

## 8. Wenn etwas nicht klappt

### Der erste Schritt: aus einem Fenster heraus starten

Der wichtigste Rat für Windows: Starten Sie das Skript **in einem Fenster**, nicht
per Doppelklick. Dann sehen Sie jede Meldung.

In der **PowerShell**:

```powershell
cd C:\Pfad\zu\sqlClipboardFormatter
.\start.bat -Pruefen
```

Was Sie dort sehen, sagt fast immer, woran es liegt. Die Meldungen, die das
Skript ausgibt:

| Meldung                                                                       | Ursache                                                                                                     |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `Fehler: kein Java gefunden. Java 17 oder neuer installieren.`                | Java fehlt oder ist zu alt — Schritt 3                                                                      |
| `Fehler: Jar fehlt oder ist veraltet, aber weder mvnw.cmd noch mvn gefunden.` | Im entpackten ZIP fehlt das fertige Programm — etwa weil der Ordner `target/` gelöscht wurde. Im Quelltext: Maven fehlt, obwohl noch nicht übersetzt wurde |
| `Fehler: der Build ist fehlgeschlagen.`                                       | Beim Übersetzen ging etwas schief — die ausführliche Meldung steht darüber                                  |
| `Fehler: "target\SqlClipboardFormatter-0.2.jar" fehlt trotz Bauvorgang.`      | Der Build hat nichts erzeugt. Im Namen steckt die Versionsnummer, sie wandert mit jedem Versionswechsel mit |

### „Java wurde nicht gefunden", obwohl Java installiert ist

Das Skript sucht der Reihe nach im Ordner `jre/`, in der Variable
`SQLFORMATTER_JAVA`, in `JAVA_HOME` und zuletzt im Suchpfad. Steht in
`start.bat -Pruefen` weder `(mitgeliefert)` noch `(JAVA_HOME)`, ist Ihr Java an
einer Stelle installiert, die der Skript nicht kennt. Setzen Sie dann
`JAVA_HOME` — oder nehmen Sie Java in den Suchpfad auf:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Java\jdk-17", "User")
```

Danach PowerShell neu öffnen. Der Pfad muss auf den Ordner zeigen, in dem
`bin` liegt, **nicht** auf die `java.exe`.

### „mvn" wird nicht gefunden, obwohl Maven installiert ist

Das Skript sucht in dieser Reihenfolge: im Projekt mitgelieferter Wrapper,
dann `mvn` im Suchpfad, dann `%MAVEN_HOME%\bin\mvn.cmd`. Es sagt Ihnen im
Prüfmodus, was davon gefunden wurde:

```bat
.\start.bat -Pruefen
```

Steht dort `Maven   : nicht gefunden`, ist keiner der drei Wege sichtbar.
Prüfen Sie in dieser Reihenfolge:

1. Ist `mvn.cmd` im Suchpfad? In einer neuen PowerShell:
   
   ```powershell
   where.exe mvn
   ```
   
   Findet der Befehl nichts, hilft nur der nächste Punkt.
2. Ist `MAVEN_HOME` gesetzt und zeigt auf den Ordner **mit** `bin` darin?
   
   ```powershell
   $env:MAVEN_HOME
   ```
   
   Bei einem leeren Ergebnis hilft nur Punkt 3.
3. Tragen Sie Maven im Suchpfad ein, oder setzen Sie `MAVEN_HOME`:
   
   ```powershell
   [Environment]::SetEnvironmentVariable("MAVEN_HOME", "C:\tools\apache-maven-3.9.9", "User")
   ```

Danach PowerShell neu öffnen — die Umgebung wird erst beim Start gelesen.

> Ein Maven-Pfad mit Leerzeichen im Namen, wie `C:\Program Files\...`,
> funktioniert inzwischen. Das war früher ein Fehler und ist behoben.

### Beim Schließen des schwarzen Fensters: „Terminate batch job (Y/N)?"

Wenn Sie das schwarze Fenster schließen, während das Programm läuft, fragt
Windows das. Antworten Sie mit **N** oder **J** — je nachdem, ob Sie das
Programm behalten wollen. Besser: beenden Sie über das Programmfenster selbst.

### „unrecognized option" beim Start

Dann steht ein Schalter an falscher Stelle. Die beiden eigenen Schalter
`-Neu` und `-Pruefen` gehören **an den Anfang**, alles andere (zum Beispiel
Speicherangaben) dahinter:

```powershell
.\start.bat -Pruefen -Xmx512m
```

### Der Knopf „Formatieren" ist grau

Die Statuszeile erklärt es: In der Zwischenablage steht kein SQL. Drücken Sie
**Einfügen** und schauen Sie nach, ob die Statuszeile umspringt.

### „error: could not find or load main class" oder „unable to access jarfile"

Das Programm wurde noch nicht übersetzt. Führen Sie einmal aus:

```powershell
.\start.bat -Neu
```

### Das Programmfenster öffnet sich, ist aber nicht zu sehen

Es kann hinter dem gerade benutzten Programm liegen. Starten Sie es über die
Tastenkombination aus Abschnitt 7 erneut — liegt es noch offen, kommt es nach
vorn, statt ein zweites zu öffnen.

### Windows blockiert das Skript

Kommt die Meldung eines Virensenscanners, ist das eine Fehlmeldung: `start.bat`
ist eine reine Textdatei, die Java startet. Starten Sie es in dem Fall aus dem
Explorer heraus mit **Rechtsklick → Ausführen** oder in der PowerShell, wie in
Abschnitt 8 beschrieben.

## 9. Was das Programm nicht macht

- **Kein Internet.** Es sendet nichts nach außen. Den SQL-Text sehen nur Sie
  und Ihre Zwischenablage.
- **Keine Datenbankverbindung.** Das Programm kennt SQL nur als Text.
- **Kein Speichern.** Es legt keine Dateien an und liest keine. Was Sie
  formatieren, verlassen Sie nur als kopierter Text die Zwischenablage.
- **Kein Zurück.** Die ursprüngliche Fassung liegt nach dem Formatieren nicht
  mehr in der Zwischenablage — kopieren Sie sie vorher noch einmal, wenn Sie
  beide Fassungen brauchen.

## 10. Auf einen anderen Rechner umziehen

Kopieren Sie den Ordner `sqlClipboardFormatter` auf den neuen Rechner. Ist dort
noch kein Java installiert, holen Sie das zuerst nach. Einmal übersetzt läuft
das Programm ohne Maven weiter.

Mehr zum Programm selbst — Bauen, Aufbau, Tests, Versionierung — steht in der
[README](../README.md).
