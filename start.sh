#!/usr/bin/env bash
#
# Startet den SQL-Clipboard-Formatter.
#
# Das Skript baut bei Bedarf und startet danach das gepackte Jar. Ohne diesen
# Schritt gaebe es "unable to access jarfile", weil target/ nach einem
# "mvn clean" leer ist.
#
#   ./start.sh            normales Starten
#   ./start.sh -Neu       vorher neu bauen, auch wenn das Jar aktuell ist
#   ./start.sh -Pruefen   alles pruefen, aber weder bauen noch starten
#
# Weitere Argumente werden an die JVM durchgereicht. Unter Windows gibt es
# start.bat mit derselben Wirkung.
#
# Auf macOS per Doppelklick startbar: eine Datei namens start.command
# aus diesem Skript machen (Umbenennen genuegt).

set -euo pipefail

cd "$(dirname "$0")"

fehler() {
    echo "Fehler: $*" >&2
    exit 1
}

# Der Jar-Name traegt die Version aus der pom.xml. Hier fest eingetragen stand
# er vorher - und waere bei jedem Versionswechsel stillschweigend falsch
# geworden, mitten im Startvorgang als "unable to access jarfile".
#
# "head -1" ist die Projektversion: sie steht in Zeile 9, die Abhaengigkeiten
# kommen danach. Eine Abhaengigkeit, die sich vor dem Projekt aendert, wuerde
# das brechen - dann greift aber der Leer-Test unten und meldet es.
  # Im Repository steht der Name im pom.xml. Im Release-ZIP gibt es keine
  # pom.xml - dort benennt das Jar selbst die Version. Ohne diesen zweiten
  # Weg waere das Starten eines entpackten ZIPs unmoeglich, ohne genau die
  # Datei mitzuschleppen, die ein Endnutzer nicht braucht.
  # Das "|| true" ist noetig, nicht kosmetisch: ohne pom.xml liefert sed
  # Fehler 2, unter "set -o pipefail" gilt damit die ganze Pipe als
  # fehlgeschlagen, und "set -e" beendet das Skript an dieser Zeile - noch
  # bevor es etwas ausgeben kann. Genau das tat der erste Versuch, und ohne
  # Ausgabe sieht ein Absturz wie ein Skriptfehler aus.
  VERSION=$(sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' pom.xml 2>/dev/null | head -1 || true)
  JAR=""
  if [ -n "$VERSION" ] && [ -f "target/SqlClipboardFormatter-$VERSION.jar" ]; then
      JAR="target/SqlClipboardFormatter-$VERSION.jar"
  else
      # Eine unpassende Musterdatei wird von der Pruefung oben verworfen, das
      # Muster selbst ist nur ein unveraenderter Text und keine Datei.
      for kandidat in target/SqlClipboardFormatter-*.jar; do
          [ -f "$kandidat" ] || continue
          JAR="$kandidat"
      done
  fi
  # Kein Abbruch, wenn nichts gefunden wurde: "-Pruefen" soll genau diesen
  # Zustand melden und nicht an ihm scheitern - waehrend "mvn verify" laeuft
  # das Skript in den Tests, und da gibt es das fertige Jar noch gar nicht.
  # Der Startpfad weiter unten faengt das sauber ab.
  [ -n "$JAR" ] || JAR="target/SqlClipboardFormatter-$VERSION.jar"

# Argumente: -Neu abfangen, der Rest geht an die JVM.
#
# Beim Ausgeben des Arrays steht "${JVM_ARGUMENTE[@]+...}" statt
# "${JVM_ARGUMENTE[@]}": macOS liefert bash 3.2, und dort ist die Expansion
# eines leeren Arrays unter "set -u" ein Fehler ("unbound variable"). Startet
# man ohne Argumente, waere das Array leer - das Skript brach genau dann ab.
# Mit "+" wird das Array nur angesprochen, wenn es gesetzt ist, in 3.2 wie 5.x.
#
# Genau eine Stelle im Skript expandiert das Array, danach wandert es in die
# Positionsparameter. Zwei Gruende: "$@" ist unter bash 3.2 mit set -u
# unproblematisch (nur die leere Array-Expansion nicht), und der Pruefmodus
# gibt damit dieselbe Zeile aus, die spaeter der exec benutzt - so schuetzt
# ein Test genau den Ausdruck, der einmal das ganze Skript abgebrochen hat.
NEUBAUEN=0
PRUEFEN=0
JVM_ARGUMENTE=()
for arg in "$@"; do
    if [ "$arg" = "-Neu" ] || [ "$arg" = "--neu" ]; then
        NEUBAUEN=1
    elif [ "$arg" = "-Pruefen" ] || [ "$arg" = "--pruefen" ]; then
        PRUEFEN=1
    else
        JVM_ARGUMENTE+=("$arg")
    fi
done
set -- ${JVM_ARGUMENTE[@]+"${JVM_ARGUMENTE[@]}"}

# --- Konfiguration ----------------------------------------------------------
# start.local.conf gehoert neben dieses Skript, wird aber nicht mitversioniert:
# start.conf.example ist die Vorlage, die Kopie steht in der .gitignore. Ohne
# die Datei laeuft alles wie bisher - sie ist optional und nie Pflicht.
#
# Gelesen wird genauso wie in start.bat, damit beide Skripte dieselbe Datei
# gleich verstehen. Das ist hier ausfuehrbar und wird unten auch getestet,
# waehrend die Batch-Variante nur auf einem Windows-Reigner laufbar ist.
#
# Eine kaputte Zeile wird uebersprungen, nie zum Abbruch. Bei "set -e" waere
# ein unerwartetes Muster sonst ein stiller Abbruch, und aus einem
# Tippfehler darf kein "das Programm startet nicht mehr" werden.
CONF="./start.local.conf"
CFG_JAVA=""
CFG_MAVEN=""
CFG_MAVENJDK=""
if [ -f "$CONF" ]; then
    while IFS= read -r zeile || [ -n "$zeile" ]; do
        zeile="${zeile#"${zeile%%[![:space:]]*}"}"
        case "$zeile" in
            '' | \#*) continue ;;
            *=*) ;;
            *) continue ;;
        esac
        schluessel="${zeile%%=*}"
        wert="${zeile#*=}"
        # Alles ausser Buchstaben, Ziffern und Bindestrich aus dem Schluessel
        # entfernen: das macht fuehrende Leerzeichen und ein BOM vom Editor
        # harmlos, ohne eine Sonderbehandlung fuer Bytes zu brauchen.
        schluessel=$(printf '%s' "$schluessel" | tr -cd 'A-Za-z0-9-' | tr 'A-Z' 'a-z')
        wert="${wert#"${wert%%[![:space:]]*}"}"
        wert="${wert%"${wert##*[![:space:]]}"}"
        [ -n "$wert" ] || continue
        case "$schluessel" in
            java) CFG_JAVA="$wert" ;;
            maven) CFG_MAVEN="$wert" ;;
            maven-jdk) CFG_MAVENJDK="$wert" ;;
        esac
    done < "$CONF"
fi

# --- Java suchen -----------------------------------------------------------
# Nicht "which java": das findet auch das macOS-Stub unter
# /usr/bin/java, das nur "Java ist nicht installiert" ausgibt und mit
# Fehler 1 endet. -version klappt die Candidate wirklich auf.
# Reihenfolge: mitgelieferte Laufzeit, start.local.conf, SQLFORMATTER_JAVA,
# JAVA_HOME, PATH.
# Die mitgelieferte gewinnt, weil sie die einzige ist, von der wir wissen,
# dass sie zum Jar passt. Jeder Zweig prueft mit -version - ein halb
# entpackter Download darf den Start nicht verhindern, wenn es ein
# zweites Java gibt.
JAVA=""
QUELLE=""
if [ -x "./jre/bin/java" ] && ./jre/bin/java -version >/dev/null 2>&1; then
    JAVA="./jre/bin/java"
    QUELLE="mitgeliefert"
elif [ -n "$CFG_JAVA" ] && [ -x "$CFG_JAVA" ] \
        && "$CFG_JAVA" -version >/dev/null 2>&1; then
    JAVA="$CFG_JAVA"
    QUELLE="start.local.conf"
elif [ -n "${SQLFORMATTER_JAVA:-}" ] && [ -x "$SQLFORMATTER_JAVA" ] \
        && "$SQLFORMATTER_JAVA" -version >/dev/null 2>&1; then
    JAVA="$SQLFORMATTER_JAVA"
    QUELLE="SQLFORMATTER_JAVA"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] \
        && "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
    JAVA="$JAVA_HOME/bin/java"
    QUELLE="JAVA_HOME"
elif kandidat="$(command -v java 2>/dev/null)" && [ -n "$kandidat" ] \
        && "$kandidat" -version >/dev/null 2>&1; then
    # "command -v", nicht "[ -x java ]": das testet eine Datei namens "java"
    # im Arbeitsverzeichnis und findet die im PATH nie.
    JAVA="$kandidat"
    QUELLE="PATH"
elif [ -x /usr/libexec/java_home ]; then
    # macOS ohne JDK im PATH: java_home liefert das Basisverzeichnis, nicht
    # die Laufzeit - selbst starten waere falsch.
    basis="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -n "$basis" ] && [ -x "$basis/bin/java" ] \
            && "$basis/bin/java" -version >/dev/null 2>&1; then
        JAVA="$basis/bin/java"
        QUELLE="java_home"
    fi
fi
[ -n "$JAVA" ] || fehler "kein Java gefunden. Java 17 oder neuer installieren."

# --- Maven suchen -----------------------------------------------------------
# Einmal ermittelt und in MVN abgelegt, damit der Pruefmodus und der Build
# dieselbe Erkennung benutzen. Ohne Maven laesst sich ein fehlendes Jar nicht
# erzeugen - das ist dann ein klarer Fehler und kein stilles Scheitern.
MVN=""
if [ -n "$CFG_MAVEN" ] && [ -x "$CFG_MAVEN" ]; then
    MVN="$CFG_MAVEN"
elif [ -x ./mvnw ]; then
    MVN="./mvnw"
elif command -v mvn >/dev/null 2>&1; then
    MVN="mvn"
fi

# --- Pruefmodus ------------------------------------------------------------
# Prueft die Erkennung, baut aber nicht und startet nicht. Der Modus ist fuer
# den Test da: genau die Expansion des leeren Arrays unter "set -u" hat das
# Skript auf bash 3.2 einmal laut mit "unbound variable" abbrechen lassen, und
# das sieht man nur beim Ausfuehren, nicht beim Lesen.
if [ "$PRUEFEN" = 1 ]; then
    # Die Quelle mit anzeigen: "Java: /pfad/zum/java" allein laesst offen,
    # ob das nun das mitgelieferte ist oder ein irgendwo installiertes.
    echo "Java    : $JAVA  ($QUELLE)"
    # Die Konfigurationszeile steht hier und nicht weiter unten, damit
    # start.sh und start.bat dieselbe Reihenfolge zeigen. Beide Ausgaben
    # werden nebeneinander in der Anleitung abgedruckt.
    if [ -f "$CONF" ]; then echo "Konfig  : $CONF"; else echo "Konfig  : keine"; fi
    echo "Maven   : ${MVN:-nicht gefunden}"
    # Pfad mit ausgeben: dann sieht man beim Pruefen sofort, ob die Version
    # aus der pom.xml wirklich die ist, die gebaut wurde.
    echo "Jar     : $JAR ($([ -f "$JAR" ] && echo vorhanden || echo fehlt))"
    echo "Bauen   : $([ "$NEUBAUEN" = 1 ] && echo ja || echo "falls Quellen neuer")"
    echo "Argumente: $# an die JVM"
    for a in "$@"; do echo "  [$a]"; done
    exit 0
fi

# --- Bauen, wenn noetig ----------------------------------------------------
# Am Build-Datum im Manifest erkennbar: das steht in der Fusszeile.
if [ "$NEUBAUEN" = 1 ] || [ ! -f "$JAR" ] \
        || [ -n "$(find src pom.xml -newer "$JAR" 2>/dev/null)" ]; then
    [ -n "$MVN" ] || fehler "weder ./mvnw noch mvn gefunden - Jar kann nicht gebaut werden."
    echo "Bauen ..."
    # Maven startet seine eigene JVM ueber JAVA_HOME - dieselbe Falle wie in
    # start.bat. Der Eintrag wird nur diesem einen Aufruf mitgegeben, damit
    # der Start der App unberuehrt bleibt. Ohne Eintrag bleibt JAVA_HOME,
    # wie es ohne diese Datei immer war.
    if [ -n "$CFG_MAVENJDK" ]; then
        JAVA_HOME="$CFG_MAVENJDK" "$MVN" -q -DskipTests package
    else
        "$MVN" -q -DskipTests package
    fi
fi

[ -f "$JAR" ] || fehler "$JAR fehlt trotz Bauvorgang."

# --- Starten ---------------------------------------------------------------
# -Dapple.laf.useScreenMenuBar=false: sonst legt macOS die Menueleiste ueber
# das Fenster und der erste Klick geht ins Leere.
exec "$JAVA" -Dapple.laf.useScreenMenuBar=false -jar "$JAR" "$@"
