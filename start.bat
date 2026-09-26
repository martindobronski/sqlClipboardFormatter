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
set "VERSION="
for /f "usebackq delims=" %%v in (`powershell -NoProfile -Command "((Select-String -Path pom.xml -Pattern '<version>([^<]+)</version>' | Select-Object -First 1).Matches[0].Groups[1].Value)" 2^>nul`) do (
    if not defined VERSION set "VERSION=%%v"
)
if not defined VERSION (
    echo Fehler: Version in der pom.xml nicht gefunden. 1>&2
    exit /b 1
)
set "JAR=target\SqlClipboardFormatter-%VERSION%.jar"
set "NEUBAUEN="
set "ZUSATZ="
set "BAUEN="

rem --- Argumente ------------------------------------------------------------
rem Nicht "if ... set ... & shift & goto": das & trennt die Befehle
rem unbedingt, auch wenn die Bedingung nicht zutrifft. Dann wuerden die
rem Argumente stillschweigend verschluckt.
:argumente
if "%~1"=="" goto javaSuchen
if /i "%~1"=="-Neu" goto neu
set "ZUSATZ=%ZUSATZ% %1"
shift
goto argumente
:neu
set "NEUBAUEN=1"
shift
goto argumente

rem --- Java suchen ----------------------------------------------------------
:javaSuchen
rem Reihenfolge: JAVA_HOME, dann der erste java im PATH, dann Fehler. Ohne
rem JAVA_HOME liegt auf Windows oft etwas unter "%ProgramFiles%\Java", aber
rem der Pfad aendert sich mit jedem JDK-Update - ein veralteter Pfad ist
rem schlimmer als eine klare Meldung.
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA "%%j" -version >nul 2>&1 && set "JAVA=%%j"
if not defined JAVA (
    echo Fehler: kein Java gefunden. Java 17 oder neuer installieren. 1>&2
    echo         Falls installiert: JAVA_HOME setzen oder Java in den PATH aufnehmen. 1>&2
    exit /b 1
)

rem --- Maven suchen ---------------------------------------------------------
rem Der Wrapper des Projekts hat Vorrang: er bringt die passende
rem Maven-Version selbst mit, eine globale Installation koennte zu alt sein.
set "MVN="
if exist "mvnw.cmd" set "MVN=call mvnw.cmd"
if not defined MVN for /f "delims=" %%m in ('where mvn 2^>nul') do if not defined MVN set "MVN=%%m"
if not defined MVN if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"

rem --- Muss gebaut werden? -------------------------------------------------
set "PRUEFUNG=$m=[datetime]'1601-01-01'; foreach ($i in (Get-ChildItem -Recurse -File -Path src,pom.xml)) { if ($i.LastWriteTime -gt $m) { $m=$i.LastWriteTime } }; if ((Get-Item '%JAR%').LastWriteTime -lt $m) { 'BAUEN' } else { 'OK' }"

if defined NEUBAUEN set "BAUEN=1"
if not defined NEUBAUEN if not exist "%JAR%" set "BAUEN=1"
if not defined NEUBAUEN if exist "%JAR%" call :istVeraltet

if defined BAUEN goto bauen
goto starten

rem --- Bauen ----------------------------------------------------------------
:bauen
if not defined MVN (
    echo Fehler: Jar fehlt oder ist veraltet, aber weder mvnw.cmd noch mvn gefunden. 1>&2
    echo         Maven installieren, oder "%JAR%" loeschen sobald Maven da ist. 1>&2
    exit /b 1
)
echo Bauen ...
%MVN% -q -DskipTests package
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
