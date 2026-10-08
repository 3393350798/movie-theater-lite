param(
    [string]$StudentId = "student",
    [string]$StudentName = "author"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$releaseRoot = Join-Path $projectRoot "release"
$packageName = "$StudentId-$StudentName-movie-theater-submit"
$packageDir = Join-Path $releaseRoot $packageName
$jarName = "movie-theater-system.jar"
$zipPath = Join-Path $releaseRoot "$packageName.zip"

function Ensure-CleanDirectory {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Path | Out-Null
}

function Copy-DirectoryContent {
    param(
        [string]$Source,
        [string]$Destination
    )
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Copy-Item -Path (Join-Path $Source "*") -Destination $Destination -Recurse -Force
}

Set-Location $projectRoot

& (Join-Path $projectRoot "build.bat")
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed. Package generation stopped."
}

Ensure-CleanDirectory $releaseRoot
Ensure-CleanDirectory $packageDir

$jarDir = Join-Path $packageDir "jar"
$srcDir = Join-Path $packageDir "src"
$dbDir = Join-Path $packageDir "database"
$tableDir = Join-Path $dbDir "tables"
$libDir = Join-Path $packageDir "lib"
New-Item -ItemType Directory -Path $jarDir, $srcDir, $dbDir, $tableDir, $libDir | Out-Null

$manifestPath = Join-Path $releaseRoot "MANIFEST.MF"
@(
    "Manifest-Version: 1.0"
    "Main-Class: movietheater.client.MovieTheaterClientLauncher"
    ""
) | Set-Content -LiteralPath $manifestPath -Encoding ASCII

$jarExe = (Get-Command jar.exe -ErrorAction Stop).Source
Push-Location (Join-Path $projectRoot "out")
try {
    & $jarExe cfm (Join-Path $jarDir $jarName) $manifestPath .
    if ($LASTEXITCODE -ne 0) {
        throw "JAR generation failed."
    }
}
finally {
    Pop-Location
}
Remove-Item -LiteralPath $manifestPath -Force

Copy-DirectoryContent -Source (Join-Path $projectRoot "src") -Destination $srcDir
$schemaPath = Join-Path $projectRoot "schema.sql"
$schemaText = Get-Content -LiteralPath $schemaPath -Raw

$dbTablesChineseName = -join ([char[]](25968, 25454, 24211, 34920, 46, 115, 113, 108))
Copy-Item -LiteralPath $schemaPath -Destination (Join-Path $dbDir $dbTablesChineseName) -Force

$tableNames = @("users", "rooms", "room_members", "videos", "chats")
foreach ($tableName in $tableNames) {
    $escapedTableName = [regex]::Escape($tableName)
    $pattern = "(?ms)CREATE TABLE IF NOT EXISTS\s+$escapedTableName\s*\(.*?\)\s*ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
    $match = [regex]::Match($schemaText, $pattern)
    if (-not $match.Success) {
        throw "Cannot find CREATE TABLE statement for $tableName."
    }
    $tableSql = "USE movie_theater;`r`n`r`n" + $match.Value + "`r`n"
    Set-Content -LiteralPath (Join-Path $tableDir "$tableName.sql") -Value $tableSql -Encoding UTF8
}

$mysqlDriver = Join-Path (Split-Path -Parent $projectRoot) "lib\mysql-connector-j-9.4.0.jar"
if (Test-Path -LiteralPath $mysqlDriver) {
    Copy-Item -LiteralPath $mysqlDriver -Destination $libDir -Force
}

Copy-Item -LiteralPath (Join-Path $projectRoot "server.properties.example") -Destination $packageDir -Force

$readmeTarget = Join-Path $packageDir "README.txt"
$readmeText = Get-Content -LiteralPath (Join-Path $projectRoot "submit_README.txt") -Raw -Encoding UTF8
[System.IO.File]::WriteAllText($readmeTarget, $readmeText, [System.Text.Encoding]::UTF8)

@"
@echo off
cd /d "%~dp0"
java -jar "jar\$jarName"
pause
"@ | Set-Content -LiteralPath (Join-Path $packageDir "run_client_from_jar.bat") -Encoding ASCII

@"
@echo off
cd /d "%~dp0"
java -cp "jar\$jarName;lib\mysql-connector-j-9.4.0.jar" movietheater.server.MovieTheaterServer
pause
"@ | Set-Content -LiteralPath (Join-Path $packageDir "run_server_from_jar.bat") -Encoding ASCII

if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
Compress-Archive -LiteralPath $packageDir -DestinationPath $zipPath -Force

Write-Host "Package created:"
Write-Host $zipPath
