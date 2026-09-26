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
VERSION=$(sed -n 's|.*<version>\([^<]*\)</version>.*|\1|p' pom.xml | head -1)
[ -n "$VERSION" ] || fehler "Version in der pom.xml nicht gefunden."
JAR="target/SqlClipboardFormatter-$VERSION.jar"

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

# --- Java suchen -----------------------------------------------------------
# Nicht "which java": das findet auch das macOS-Stub unter
# /usr/bin/java, das nur "Java ist nicht installiert" ausgibt und mit
# Fehler 1 endet. -version klappt die Candidate wirklich auf.
JAVA=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] \
        && "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
    JAVA="$JAVA_HOME/bin/java"
elif kandidat="$(command -v java 2>/dev/null)" && [ -n "$kandidat" ] \
        && "$kandidat" -version >/dev/null 2>&1; then
    # "command -v", nicht "[ -x java ]": das testet eine Datei namens "java"
    # im Arbeitsverzeichnis und findet die im PATH nie.
    JAVA="$kandidat"
elif [ -x /usr/libexec/java_home ]; then
    # macOS ohne JDK im PATH: java_home liefert das Basisverzeichnis, nicht
    # die Laufzeit - selbst starten waere falsch.
    basis="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [ -n "$basis" ] && [ -x "$basis/bin/java" ] \
            && "$basis/bin/java" -version >/dev/null 2>&1; then
        JAVA="$basis/bin/java"
    fi
fi
[ -n "$JAVA" ] || fehler "kein Java gefunden. Java 17 oder neuer installieren."

# --- Maven suchen -----------------------------------------------------------
# Einmal ermittelt und in MVN abgelegt, damit der Pruefmodus und der Build
# dieselbe Erkennung benutzen. Ohne Maven laesst sich ein fehlendes Jar nicht
# erzeugen - das ist dann ein klarer Fehler und kein stilles Scheitern.
MVN=""
if [ -x ./mvnw ]; then
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
    echo "Java    : $JAVA"
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
    "$MVN" -q -DskipTests package
fi

[ -f "$JAR" ] || fehler "$JAR fehlt trotz Bauvorgang."

# --- Starten ---------------------------------------------------------------
# -Dapple.laf.useScreenMenuBar=false: sonst legt macOS die Menueleiste ueber
# das Fenster und der erste Klick geht ins Leere.
exec "$JAVA" -Dapple.laf.useScreenMenuBar=false -jar "$JAR" "$@"
