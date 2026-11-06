# forgeward_stopguard -- hand build (javac + jar, no Gradle).
# Rebuild:  powershell -ExecutionPolicy Bypass -File build.ps1
# Override paths with env vars JDK / SERVER if they ever move.
$ErrorActionPreference = "Stop"

if ($env:JDK) { $JDK = $env:JDK } elseif ($env:JAVA_HOME) { $JDK = $env:JAVA_HOME } else { $JDK = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" }
if ($env:SERVER) { $SERVER = $env:SERVER } else { $SERVER = Join-Path $PSScriptRoot "..\..\server" }   # a NeoForge 21.1.250 server dir (its libraries/)
$PROJ = $PSScriptRoot

$JAVAC = Join-Path $JDK "bin\javac.exe"
$JAR   = Join-Path $JDK "bin\jar.exe"

$LIB  = Join-Path $SERVER "libraries"
$MODS = Join-Path $SERVER "mods"
$NF   = Join-Path $LIB "net\neoforged\neoforge\21.1.250"
$MC   = Join-Path $LIB "net\minecraft\server\1.21.1-20240808.144430"

$CREATE     = Join-Path $NF "neoforge-21.1.250-universal.jar"
$SPONGE     = Join-Path $LIB  "net\fabricmc\sponge-mixin\0.15.2+mixin.0.8.7\sponge-mixin-0.15.2+mixin.0.8.7.jar"

$BUILD  = Join-Path $PROJ "build"
$LIBS   = Join-Path $PROJ "libs"
$OUT    = Join-Path $BUILD "classes"
$JAROUT = Join-Path $PROJ "forgeward_stopguard-1.0.1.jar"

if (Test-Path $BUILD) { Remove-Item -Recurse -Force $BUILD }
New-Item -ItemType Directory -Force -Path $OUT  | Out-Null
New-Item -ItemType Directory -Force -Path $LIBS | Out-Null

# MixinExtras is compile-only (provided by NeoForge at runtime); pull the jar-in-jar
# out of the NeoForge universal jar so the build stays self-contained.
$MEX = Join-Path $LIBS "mixinextras-neoforge-0.5.3.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$uni = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $NF "neoforge-21.1.250-universal.jar"))
try {
    $entry = $uni.GetEntry("META-INF/jarjar/mixinextras-neoforge-0.5.3.jar")
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $MEX, $true)
} finally { $uni.Dispose() }

$CP = @(
    (Join-Path $NF "neoforge-21.1.250-server.jar"),
    (Join-Path $NF "neoforge-21.1.250-universal.jar"),
    (Join-Path $LIB "com\mojang\logging\1.2.7\logging-1.2.7.jar"),
    (Join-Path $LIB "com\mojang\brigadier\1.3.10\brigadier-1.3.10.jar"),   # Component implements Message (compile only)
    (Join-Path $LIB "org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar"),
    (Join-Path $MC "server-1.21.1-20240808.144430-srg.jar"),
    (Join-Path $LIB "net\neoforged\bus\8.0.5\bus-8.0.5.jar"),   # IEventBus: the self-report listeners
    (Join-Path $LIB "net\neoforged\fancymodloader\loader\4.0.44\loader-4.0.44.jar"),   # @Mod (compile only)
    (Join-Path $LIB "net\neoforged\mergetool\2.0.0\mergetool-2.0.0-api.jar"),   # Dist (FMLEnvironment.dist; compile only)
    $CREATE, $SPONGE, $MEX
) -join ";"

Write-Host "[1/2] javac (Java 21) ..."
$SRC = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $PROJ "src\main\java") | ForEach-Object { $_.FullName })
& $JAVAC -proc:none -encoding UTF-8 --release 21 -classpath $CP -d $OUT $SRC
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[2/2] jar ..."
$RES = Join-Path $PROJ "src\main\resources"
if (Test-Path $JAROUT) { Remove-Item -Force $JAROUT }
& $JAR --create --file $JAROUT -C $OUT . -C $RES .
if ($LASTEXITCODE -ne 0) { throw "jar failed ($LASTEXITCODE)" }

Write-Host "Built: $JAROUT"
& $JAR --list --file $JAROUT
