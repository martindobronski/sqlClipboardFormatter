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
rem Muster selbst ist nur unveraenderter Text und keine Datei.
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
if "%~1"=="" goto konfiguration
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
:konfiguration
rem --- Konfiguration ---------------------------------------------------------
rem start.local.conf gehoert neben diese Datei, wird aber nicht mitversioniert:
rem start.conf.example ist die Vorlage, die Kopie steht in der .gitignore.
rem Ohne die Datei laeuft alles wie bisher - sie ist optional und nie Pflicht.
rem
rem Gelesen wird mit einer Zeile PowerShell, weil Batch an den Kanten
rem scheitert, ohne zu sagen wo: ein BOM aus dem Editor macht aus dem
rem Schluesselnamen Muell, ein fuehrendes Leerzeichen likewise, und beide
rem Fehler waeren still. PowerShell ist ohnehin schon noetig - fuer die
rem Version weiter oben und die Veraltungspruefung weiter unten.
rem
rem Der Pfad steht in einer Umgebungsvariablen statt im Skripttext. Ein Pfad
rem mit Anfuehrungszeichen im Namen wuerde sonst die ganze Zeile zerlegen, und
rem Batch laesst sich dazu nicht fehlerfrei zitieren.
rem
rem Eine kaputte Zeile wird uebersprungen, nie zum Abbruch: aus einem
rem Tippfehler darf kein "das Programm startet nicht mehr" werden.
rem
rem Ausgabe ist Write-Output, nicht Write-Host: Batch liest hier aus einer
rem Pipe, und der Weg in diese Pipe fuehrt ueber den Erfolgsstrom. Write-Host
rem gehoert zum Host und ist damit die falsche Quelle fuer Maschinenlesbar -
rem auch wenn er auf diesem Rechner in der Pipe ankam.
set "SQLFORMATTER_CONF=%~dp0start.local.conf"
set "CFG_JAVA="
set "CFG_MAVEN="
set "CFG_MAVENJDK="
if exist "%SQLFORMATTER_CONF%" for /f "usebackq tokens=1,* delims==" %%a in (`powershell -NoProfile -Command "foreach ($z in [System.IO.File]::ReadAllLines($env:SQLFORMATTER_CONF)) { $t = $z.TrimStart(); if ($t -and -not $t.StartsWith('#') -and $t.Contains('=')) { $p = $t -split '=', 2; if ($p[1].Trim()) { $k = ($p[0] -replace '[^A-Za-z0-9-]', '').ToUpper(); Write-Output ($k + '=' + $p[1].Trim()) } } }" 2^>nul`) do (
    rem %%a ist der Schluessel, %%b der Wert: bei "tokens=1,*" gehoert der Rest
    rem in die Variable direkt hinter der letzten, also b nach a. Ein %%z gibt
    rem es nicht - und ein undefiniertes %%z bleibt als "%z" stehen, statt zu
    rem verschwinden. Genau daran ist der Eintrag gescheitert: die Datei wurde
    rem gelesen, der Wert war "%z", "%CFG_JAVA%" zeigte auf nichts, und
    rem start.bat fiel ohne ein Wort auf JAVA_HOME zurueck.
    if /i "%%a"=="JAVA" set "CFG_JAVA=%%b"
    if /i "%%a"=="MAVEN" set "CFG_MAVEN=%%b"
    if /i "%%a"=="MAVEN-JDK" set "CFG_MAVENJDK=%%b"
)
rem --- Java suchen ----------------------------------------------------------
:javaSuchen
rem Reihenfolge: mitgelieferte Laufzeit, start.local.conf, SQLFORMATTER_JAVA, JAVA_HOME, PATH.
set "JAVA="
set "QUELLE="
if not defined JAVA if exist "%~dp0jre\bin\java.exe" "%~dp0jre\bin\java.exe" -version >nul 2>&1 && set "JAVA=%~dp0jre\bin\java.exe" && set "QUELLE=mitgeliefert"
if not defined JAVA if defined CFG_JAVA if exist "%CFG_JAVA%" "%CFG_JAVA%" -version >nul 2>&1 && set "JAVA=%CFG_JAVA%" && set "QUELLE=start.local.conf"
if not defined JAVA if defined SQLFORMATTER_JAVA if exist "%SQLFORMATTER_JAVA%" "%SQLFORMATTER_JAVA%" -version >nul 2>&1 && set "JAVA=%SQLFORMATTER_JAVA%" && set "QUELLE=SQLFORMATTER_JAVA"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" "%JAVA_HOME%\bin\java.exe" -version >nul 2>&1 && set "JAVA=%JAVA_HOME%\bin\java.exe" && set "QUELLE=JAVA_HOME"
if not defined JAVA for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA "%%j" -version >nul 2>&1 && set "JAVA=%%j" && set "QUELLE=PATH"
if not defined JAVA (
    echo Fehler: kein Java gefunden. Java 17 oder neuer installieren. 1>&2
    echo         Falls installiert: JAVA_HOME setzen oder Java in den PATH aufnehmen. 1>&2
    exit /b 1
)
rem --- Maven suchen ---------------------------------------------------------
set "MVN="
rem Prioritaet 1: Der Pfad aus start.local.conf
if defined CFG_MAVEN if exist "%CFG_MAVEN%" set "MVN=%CFG_MAVEN%"
rem Prioritaet 2: Der Wrapper des Projekts
if not defined MVN if exist "mvnw.cmd" set "MVN=mvnw.cmd"
rem Prioritaet 3: Globales Maven im PATH
if not defined MVN for /f "delims=" %%m in ('where mvn 2^>nul') do if not defined MVN set "MVN=%%m"
rem Prioritaet 4: MAVEN_HOME Umgebungsvariable
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
if exist "%SQLFORMATTER_CONF%" (echo Konfig  : %SQLFORMATTER_CONF%) else (echo Konfig  : keine)
rem Was aus der Datei wirklich gelesen wurde. Ohne diese Zeilen ist ein
rem nicht uebernommener Eintrag nicht von einem leeren zu unterscheiden.
if defined CFG_JAVA echo Gelesen : Java=%CFG_JAVA%
if defined CFG_MAVEN echo Gelesen : Maven=%CFG_MAVEN%
if defined CFG_MAVENJDK echo Gelesen : Maven-Jdk=%CFG_MAVENJDK%
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

rem Maven startet seine eigene JVM ueber JAVA_HOME. Zeigt der auf eine zu
rem alte Laufzeit, schlaegt der Build fehl - und zwar genau dann, wenn auf
rem dem Rechner noch ein altes Java installiert ist. Der Pfad kommt darum
rem aus start.local.conf und nur, wenn dort etwas steht. Ohne Eintrag bleibt
rem JAVA_HOME unangetastet, wie es ohne diese Datei immer war.
rem Das setlocal/endlocal darum herum begrenzt die Aenderung auf den einen
rem Maven-Aufruf; ohne das bliebe das fremde JAVA_HOME auch fuer den Start
rem der App stehen.
setlocal
if defined CFG_MAVENJDK set "JAVA_HOME=%CFG_MAVENJDK%"

call "%MVN%" -q -DskipTests package
if errorlevel 1 (
    echo Fehler: der Build ist fehlgeschlagen. 1>&2
    endlocal
    exit /b 1
)
endlocal
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
