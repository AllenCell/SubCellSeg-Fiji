<#
.SYNOPSIS
  Build MegaSeg_Fiji-<version>.jar without Maven, using the JDK bundled with Fiji.

.EXAMPLE
  .\build.ps1                      # builds build\MegaSeg_Fiji-<version>.jar against C:\Fiji.app
  .\build.ps1 -Fiji D:\Fiji.app    # different Fiji location
  .\build.ps1 -Install             # also copies the jar into <Fiji>\plugins
#>
param(
    [string]$Fiji = "C:\Fiji.app",
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

if (-not (Test-Path (Join-Path $Fiji "jars"))) {
    throw "Fiji not found at '$Fiji' (no jars folder). Pass -Fiji <path to Fiji.app>."
}

# --- locate javac / jar: JAVA_HOME first, then the JDK that ships with Fiji
$javac = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
} else {
    $found = Get-ChildItem (Join-Path $Fiji "java") -Recurse -Filter javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $javac = $found.FullName }
}
if (-not $javac) {
    throw "No javac.exe found. Install a JDK and set JAVA_HOME, or use a Fiji that bundles a JDK."
}
$jar = Join-Path (Split-Path $javac) "jar.exe"
Write-Host "Using javac: $javac"

# --- version from pom.xml
$version = ([xml](Get-Content (Join-Path $root "pom.xml"))).project.version
$build   = Join-Path $root "build"
$classes = Join-Path $build "classes"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force $classes | Out-Null

# --- compile against everything Fiji ships (classpath wildcards are fine for javac)
$cp = (Join-Path $Fiji "jars\*") + ";" + (Join-Path $Fiji "plugins\*")
$sources = Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -source 8 -target 8 -encoding UTF-8 -nowarn -cp $cp -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --- resources (bridge script) + SciJava plugin index sanity check
Copy-Item (Join-Path $root "src\main\resources\*") $classes -Recurse -Force
$index = Join-Path $classes "META-INF\json\org.scijava.plugin.Plugin"
if (-not (Test-Path $index)) {
    throw "SciJava annotation index was not generated ($index). The scijava-common annotation processor did not run."
}

# --- package
$jarPath = Join-Path $build "MegaSeg_Fiji-$version.jar"
& $jar cf $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built $jarPath"

if ($Install) {
    $plugins = Join-Path $Fiji "plugins"
    Get-ChildItem $plugins -Filter "MegaSeg_Fiji-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jarPath $plugins -Force
    Write-Host "Installed into $plugins (restart Fiji)"
}
