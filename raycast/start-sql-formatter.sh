#!/usr/bin/env bash
#
# Raycast-Script-Command fuer den SQL-Clipboard-Formatter.
#
# Eigene Datei, damit start.sh ein neutrales Startskript fuer Terminal und
# Doppelklick bleibt und hier nichts gepflegt werden muss, was auch anderswo
# gilt.
#
# @raycast.schemaVersion 1
# @raycast.title SQL Formatter
# @raycast.description Formatiert SQL aus der Zwischenablage
# @raycast.mode silent
# @raycast.icon sql

set -euo pipefail

# Raycast startet das Skript aus dem eigenen Verzeichnis. Der Pfad laeuft daher
# relativ zum Skript: ein Umzug des Projekts darf die Datei nicht unbrauchbar
# machen, und ein fest eingetragener absoluter Pfad waere eine zweite Stelle,
# die stillschweigend falsch werden kann - genau die Sorte Fehler, die der
# Jar-Name in start.sh gerade war.
PROJEKT="$(cd "$(dirname "$0")/.." && pwd)"
START="$PROJEKT/start.sh"
[ -f "$START" ] || { echo "start.sh fehlt: $START" >&2; exit 1; }

# Laeuft schon eine Instanz? Dann nur in den Vordergrund holen. Zwei Fenster
# wuerden beide dieselbe Zwischenablage bearbeiten.
#
# Gesucht wird der Jar-Name, nicht die Hauptklasse: mit "-jar" steht die Klasse
# nicht in der Kommandozeile, der Jar-Name schon. Das Muster trifft jede
# Version und ist damit kein zweites hart eingetragenes Versionszeichen.
#
# Die Prozessnamen-Zusicherung ist nicht Beiwerk. "pgrep -f" sucht in der
# gesamten Kommandozeile - es matcht also jedes grep, jeden Editor und jede
# Shell, in der der Name einmal vorkommt. Ohne die Pruefung haette ein
# "rg SqlClipboardFormatter" im Terminal den Hotkey ins Leere laufen lassen,
# ohne jede Fehlermeldung.
INSTANZ=""
for pid in $(pgrep -f 'SqlClipboardFormatter-.*\.jar' 2>/dev/null || true); do
    # ps liefert den vollen Pfad ("/usr/bin/java"), unter Linux oft nur
    # "java" - deshalb das Muster statt eines Vergleichs.
    case "$(ps -p "$pid" -o comm= 2>/dev/null)" in
        */java | java)
            INSTANZ="$pid"
            break
            ;;
    esac
done

if [ -n "$INSTANZ" ]; then
    # Braucht "Steuerung von Computer" fuer Raycast. Steht die Berechtigung
    # nicht drin, bleibt das Fenster hinten - der Aufruf endet trotzdem still.
    #
    # Im Hintergrund und ohne Warten: fragt macOS die Berechtigung ab, darf
    # der Hotkey nicht darauf warten. Der sichtbare Dialog beantwortet sich
    # einmal, danach klappt es sofort.
    osascript -e "tell application \"System Events\" to set frontmost of (first process whose unix id is $INSTANZ) to true" >/dev/null 2>&1 &
    exit 0
fi

# Kein exec: sonst waere nach dem Aufruf nichts mehr uebrig, um einen Fehler
# anzuzeigen. "silent" schluckt naemlich jede Ausgabe, ein Fehler waere sonst
# voellig lautlos - und der haeufigste ist Maven, das eine halbe Minute
# braucht und dann scheitert.
if ! /bin/bash "$START" "$@"; then
    osascript -e 'display notification "start.sh ist fehlgeschlagen - im Terminal mit -Pruefen pruefen." with title "SQL Formatter"' >/dev/null 2>&1
    exit 1
fi
