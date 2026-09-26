@echo off
rem Startet den SQL-Clipboard-Formatter.
rem
rem Baut bei Bedarf und startet danach das gepackte Jar. Ohne diesen Schritt
rem gaebe es "unable to access jarfile", weil target\ nach einem "mvn clean"
rem leer ist.
rem
rem Aufruf:  start.bat            normales Starten
rem         start.bat -Neu       vorher neu bauen, auch wenn das Jar aktuell ist
rem
rem Weitere Argumente werden an die JVM durchgereicht.
setlocal

cd /d "%~dp0"

rem Der Jar-Name traegt die Version aus der pom.xml. Hier fest eingetragen
rem stand er vorher - und waere bei jedem Versionswechsel stillschweigend
rem falsch geworden, mitten im Startvorgang als "unable to access jarfile".
rem
rem Gelesen wird die erste <version>-Zeile, das ist die Projektversion; die
rem Abhaengigkeiten stehen darunter. sed gibt es unter Windows nicht, daher
rem PowerShell - es ist ab Windows 8 vorhanden, und Java 17 laeuft ab Windows 8.
rem
rem Ohne Pipezeichen, aus demselben Grund wie bei der Veraltungspruefung
rem weiter unten: innerhalb von for /f muss jedes | als ^| escaped werden, und
rem ein Pipebruch in einer Zeilenfortsetzung beendet das Skript ohne Meldung.
rem [regex]::Match liefert den ersten Treffer direkt - die Kette aus
rem Select-String und Select-Object laesst sich damit einsparen.
set "VERSION="
for /f "usebackq delims=" %%v in (`powershell -NoProfile -Command "[regex]::Match((Get-Content -Raw pom.xml),'<version>([^<]+)</version>').Groups[1].Value" 2^>nul`) do (
    if not defined VERSION set "VERSION=%%v"
)
rem Im Repository steht der Name im pom.xml. Im Release-ZIP gibt es keine
rem pom.xml - dort benennt das Jar selbst die Version. Ohne diesen zweiten
rem Weg waere das Starten eines entpackten ZIPs unmoeglich, ohne genau die
rem Datei mitzuschleppen, die ein Endnutzer nicht braucht.
set "JAR="
if defined VERSION if exist "target\SqlClipboardFormatter-%VERSION%.jar" set "JAR=target\SqlClipboardFormatter-%VERSION%.jar"
rem Eine unpassende Musterdatei wird von der Pruefung oben verworfen; das
rem Muster selbst ist nur ein unveraenderter Text und keine Datei.
if not defined JAR for %%j in (target\SqlClipboardFormatter-*.jar) do if exist "%%j" set "JAR=%%j"
rem Kein Abbruch, wenn nichts gefunden wurde: "-Pruefen" soll genau diesen
rem Zustand melden und nicht an ihm scheitern. Der Startpfad weiter unten
rem faengt das sauber ab.
if not defined JAR set "JAR=target\SqlClipboardFormatter-%VERSION%.jar"
set "NEUBAUEN="
set "ZUSATZ="
set "BAUEN="
set "PRUEFEN="
set "ANZAHL=0"

rem --- Argumente ------------------------------------------------------------
rem Nicht "if ... set ... & shift & goto": das & trennt die Befehle
rem unbedingt, auch wenn die Bedingung nicht zutrifft. Dann wuerden die
rem Argumente stillschweigend verschluckt.
:argumente
if "%~1"=="" goto javaSuchen
if /i "%~1"=="-Neu" goto neu
if /i "%~1"=="-Pruefen" goto pruefen
if /i "%~1"=="--pruefen" goto pruefen
set "ZUSATZ=%ZUSATZ% %1"
set /a ANZAHL+=1
shift
goto argumente
:neu
set "NEUBAUEN=1"
shift
goto argumente
:pruefen
set "PRUEFEN=1"
shift
goto argumente

rem --- Java suchen ----------------------------------------------------------
:javaSuchen
rem Reihenfolge: mitgelieferte Laufzeit, SQLFORMATTER_JAVA, JAVA_HOME, PATH.
rem Die mitgelieferte gewinnt, weil sie die einzige ist, von der wir wissen,
rem dass sie zum Jar passt. Ohne JAVA_HOME liegt auf Windows oft etwas unter
rem "%ProgramFiles%\Java", aber der Pfad aendert sich mit jedem JDK-Update -
rem ein veralteter Pfad ist schlimmer als eine klare Meldung.
rem
rem Das && muss durchgehendketten sein. Mit einem & davor liefe das zweite
rem set auch dann, wenn -version fehlschlaegt - Java und QUELLE waeren dann
rem nicht mehr dasselbe. Jeder Zweig prueft selbst mit -version, damit ein
rem halb entpackter Download den Start nicht verhindert, obwohl ein zweites
rem Java installiert ist.
rem
rem >nul und nicht >/dev/null: das ist die Umleitung nach /dev/null aus der
rem Unix-Welt. cmd.exe legt stattdessen eine Datei an und verweigert bei
rem fehlendem Verzeichnis den Dienst - die ganze Zeile wird dann gar nicht
rem erst ausgefuehrt. Wer nur Java im PATH hatte, bekam so "kein Java
rem gefunden", obwohl Java installiert war.
set "JAVA="
set "QUELLE="
if exist "%~dp0jre\bin\java.exe" "%~dp0jre\bin\java.exe" -version >nul 2>&1 && set "JAVA=%~dp0jre\bin\java.exe" && set "QUELLE=mitgeliefert"
if not defined JAVA if defined SQLFORMATTER_JAVA if exist "%SQLFORMATTER_JAVA%" "%SQLFORMATTER_JAVA%" -version >nul 2>&1 && set "JAVA=%SQLFORMATTER_JAVA%" && set "QUELLE=SQLFORMATTER_JAVA"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" "%JAVA_HOME%\bin\java.exe" -version >nul 2>&1 && set "JAVA=%JAVA_HOME%\bin\java.exe" && set "QUELLE=JAVA_HOME"
if not defined JAVA for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA "%%j" -version >nul 2>&1 && set "JAVA=%%j" && set "QUELLE=PATH"
if not defined JAVA (
    echo Fehler: kein Java gefunden. Java 17 oder neuer installieren. 1>&2
    echo         Falls installiert: JAVA_HOME setzen oder Java in den PATH aufnehmen. 1>&2
    exit /b 1
)

rem --- Maven suchen ---------------------------------------------------------
rem Der Wrapper des Projekts hat Vorrang: er bringt die passende
rem Maven-Version selbst mit, eine globale Installation koennte zu alt sein.
set "MVN="
rem In MVN steht nur der Programmpfad, niemals ein "call" davor. Sonst
rem laesst sich der Pfad nicht quoten: "%MVN%" waere dann ein Programm
rem namens "call mvnw.cmd". Das call steht deshalb an der Aufrufstelle.
if exist "mvnw.cmd" set "MVN=mvnw.cmd"
if not defined MVN for /f "delims=" %%m in ('where mvn 2^>nul') do if not defined MVN set "MVN=%%m"
rem Das "if defined MAVEN_HOME" davor ist noetig: ohne die Abfrage
rem expandiert der Pfad zu "\bin\mvn.cmd", und der Test trifft die Datei
rem im Wurzelverzeichnis des Laufwerks - ein Treffer, der nichts mit
rem Maven zu tun hat.
if not defined MVN if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"

rem --- Muss gebaut werden? -------------------------------------------------
set "PRUEFUNG=$m=[datetime]'1601-01-01'; foreach ($i in (Get-ChildItem -Recurse -File -Path src,pom.xml)) { if ($i.LastWriteTime -gt $m) { $m=$i.LastWriteTime } }; if ((Get-Item '%JAR%').LastWriteTime -lt $m) { 'BAUEN' } else { 'OK' }"

if defined NEUBAUEN set "BAUEN=1"
if not defined NEUBAUEN if not exist "%JAR%" set "BAUEN=1"
if not defined NEUBAUEN if exist "%JAR%" call :istVeraltet

if defined PRUEFEN goto pruefAusgabe
if defined BAUEN goto bauen
goto starten

rem --- Pruefmodus -----------------------------------------------------------
rem Gegenstueck zu start.sh -Pruefen: Java, Maven und der abgeleitete
rem Jar-Name werden ausgegeben, ohne gebaut oder gestartet zu werden. Ohne
rem diesen Modus waere auf Windows nur am Fenster erkennbar, ob etwas
rem funktioniert hat.
:pruefAusgabe
rem Die Quelle mit anzeigen: "Java: \pfad\zum\java" allein laesst offen, ob
rem das nun das mitgelieferte ist oder ein irgendwo installiertes.
echo Java    : %JAVA%  (%QUELLE%)
if defined MVN (echo Maven   : %MVN%) else (echo Maven   : nicht gefunden)
if exist "%JAR%" (echo Jar     : %JAR% - vorhanden) else (echo Jar     : %JAR% - fehlt)
if defined BAUEN (echo Bauen   : ja) else (echo Bauen   : falls Quellen neuer)
echo Argumente: %ANZAHL% an die JVM
if defined ZUSATZ echo             %ZUSATZ%
exit /b 0

rem --- Bauen ----------------------------------------------------------------
:bauen
if not defined MVN (
    echo Fehler: Jar fehlt oder ist veraltet, aber weder mvnw.cmd noch mvn gefunden. 1>&2
    echo         Maven installieren, oder "%JAR%" loeschen sobald Maven da ist. 1>&2
    exit /b 1
)
echo Bauen ...
rem call, weil eine .bat ohne call die Kontrolle nicht zurueckgibt. Bei einer
rem .exe wie mvn ist call harmlos, deshalb steht es hier statt in MVN. Die
rem Anfuehrungszeichen sind noetig: Maven liegt haeufig unter
rem "C:\Program Files\...", und unquotet bricht der Pfad an Leerzeichen auseinander.
call "%MVN%" -q -DskipTests package
if errorlevel 1 (
    echo Fehler: der Build ist fehlgeschlagen. 1>&2
    exit /b 1
)

rem --- Starten --------------------------------------------------------------
:starten
if not exist "%JAR%" (
    echo Fehler: "%JAR%" fehlt trotz Bauvorgang. 1>&2
    exit /b 1
)
rem ZUSATZ bleibt bewusst unquotiert, damit Argumente mit Leerzeichen
rem durchkommen.
"%JAVA%" -jar "%JAR%"%ZUSATZ%
exit /b %ERRORLEVEL%

rem --- Veraltungspruefung ---------------------------------------------------
rem Setzt BAUEN=1, wenn eine Quelldatei neuer als das Jar ist.
rem
rem Der Vergleich laeuft ueber PowerShell, weil %~t von der Systemsprache der
rem Zielmaschine abhaengt ("9/26/2026" gegenueber "26.09.2026") und ein
rem Textvergleich je nach System falsch entscheiden wuerde.
rem
rem Die Abfrage kommt ohne | aus: innerhalb von for /f muss jedes Pipezeichen
rem als ^| escaped werden, und ein Pipebruch in einer Zeilenfortsetzung ist
rem die haeufigste Ursache dafuer, dass Batch-Skripte ohne Meldung abbrechen.
rem
rem Laeuft PowerShell nicht, bleibt BAUEN leer und es wird nicht gebaut - die
rem harmlosere Richtung, denn starten laesst sich das vorhandene Jar immer.
:istVeraltet
set "BAUEN="
rem In Anfuehrungszeichen: sonst interpretiert cmd die Klammern des
rem PowerShell-Ausdrucks als eigenen Kontrollfluss.
for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "%PRUEFUNG%" 2^>nul`) do (
    if /i "%%i"=="BAUEN" set "BAUEN=1"
)
exit /b 0
