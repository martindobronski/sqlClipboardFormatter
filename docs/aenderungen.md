# Was sich geändert hat

Kurz gehalten, mit dem Grund dahinter. Wer den Grund nicht braucht, liest die
fett gedruckte Zeile und lässt den Rest stehen.

## 0.4 — 27.09.2026

**Der Theme-Schalter lässt ein Fenster im Vollbild jetzt im Vollbild.**

Wer im Vollbild auf Hell oder Dunkel schaltete, bekam danach ein kleines Fenster
oben links. Der Theme-Wechsel rief `pack()` auf, und `pack()` setzt Größe und
Lage eines Fensters auf die Vorzugsgröße zurück. Ein maximiertes Fenster verliert
dabei seinen Zustand, und ein Fenster im echten Vollbild lässt sich nicht
zurückholen — macOS führt diesen Zustand nur intern, auch `setExtendedState`
hilft nicht. Also wird im Vollbild gar nicht mehr gepackt, sondern nur im
Normalzustand, wo es nötig ist: nach dem Wechsel des Look-and-Feel haben die
Fremdkomponenten neue Rahmen, und ohne `pack()` schnitte der Rahmen die Knöpfe
ab.

Gepackt wird damit in zwei Fällen, die man unterscheiden muss. Ein maximiertes
Fenster lässt unter Windows die Taskleiste weg, füllt den Bildschirm also nie
ganz; es wird am gemeldeten Zustand erkannt. Ein Fenster im echten Vollbild
füllt den Bildschirm ganz, auf dem es liegt; daran erkennt man es. Bei zwei
Bildschirmen zählt der Bildschirm, auf dem das Fenster liegt, nicht das
Rechteck aus beiden.

Drei Tests halten die Regel fest.

## 0.3 — 27.09.2026

**Zeilennummern im Textfeld.** Sie stehen links im Textfeld, auf der Höhe der
Zeile daneben, zählen Bildschirmzeilen — eine Zeile, die umbricht, gehört mehr als
einmal mit — und wachsen mit, wenn das Fenster breiter wird. Gezeichnet werden
sie vom Textfeld selbst, im selben Zug wie der Text; die Bilder in beiden Themes
sind entsprechend neu.

Weiter neu: geordnete Theme-Farben, ein Name für die Dialektauswahl, den ein
Screenreader vorliest, und `start.local.conf` für Java, Maven und JDK auf
Rechnern, bei denen die üblichen Wege nicht greifen. Das waagerechte Scrollen
bleibt abgeschaltet, die Breite bei 84 Zeichen.

Drei Fehler, die erst der Lauf auf Windows gezeigt hat, sind mit behoben:

- **Der Zeichenzähler kam unter Windows auf zwei Zeichen mehr je Umbruch.** Er hat
  die Länge des Dokuments genommen, und die hängt davon ab, ob CRLF oder LF
  abgelegt wird. Auf einem Rechner mit gesetztem `JAVA_HOME` fällt das nicht auf.
- **Die Einträge aus `start.local.conf` wurden nie gelesen.** Bei `tokens=1,*`
  landet der Wert in der Variablen hinter der letzten — `%%b` nach `%%a`. Eine
  falsch benannte Batch-Variable verschwindet nicht, sie bleibt als Text stehen.
- **`start.bat` endete still, wenn der eingetragene Pfad auf eine `.cmd`-Datei
  zeigte.** Eine Batch-Datei ohne `call` übernimmt die Kontrolle und kehrt nicht
  zurück; der Rest des Skripts lief nie. Bei `java.exe` fällt das nicht auf, bei
  jedem `java.cmd` aus einem Versionsverwalter schon.

Die Bilder zeigen den Stand von 0.3; der Unterschied zu 0.4 ist unsichtbar.
