param(
    [switch] $Demo,
    [switch] $Build,
    [ValidateRange(1, 65535)] [int] $Port = 8095
)

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$toolchain = Join-Path $env:LOCALAPPDATA 'HodToolchain'
$localJdk = Join-Path $toolchain 'jdk-21.0.13.1+2'
$javaHomePath = $env:JAVA_HOME
if (!$javaHomePath -or !(Test-Path -LiteralPath (Join-Path $javaHomePath 'bin\java.exe'))) {
    $javaHomePath = $localJdk
}
$java = Join-Path $javaHomePath 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) {
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if (!$javaCommand) { throw 'Нужен Java 21. Укажите JAVA_HOME и повторите запуск.' }
    $java = $javaCommand.Source
} else {
    $env:JAVA_HOME = $javaHomePath
}

$artifact = Join-Path $repoRoot 'target\process-builder.jar'
Push-Location $repoRoot
try {
    if ($Build -or !(Test-Path -LiteralPath $artifact)) {
        $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
        $mavenPath = if ($maven) { $maven.Source } else { Join-Path $toolchain 'apache-maven-3.9.16\bin\mvn.cmd' }
        if (!(Test-Path -LiteralPath $mavenPath)) { throw 'Для сборки требуется Apache Maven.' }
        & $mavenPath -q package
        if ($LASTEXITCODE -ne 0) { throw 'Сборка Maven не завершилась.' }
    }
    $options = @("--server.port=$Port", "--process.demo=$(([bool]$Demo).ToString().ToLowerInvariant())")
    if ($Demo) {
        New-Item -ItemType Directory -Path (Join-Path $repoRoot '.run\demo') -Force | Out-Null
        $options += '--server.address=127.0.0.1',
            '--spring.datasource.url=jdbc:h2:file:./.run/demo/processes;DATABASE_TO_LOWER=TRUE',
            '--spring.datasource.username=sa', '--spring.datasource.password='
    }

    $runtime = Join-Path $repoRoot '.run\jars'
    New-Item -ItemType Directory -Path $runtime -Force | Out-Null
    $runningArtifact = Join-Path $runtime ('process-builder-' + [Guid]::NewGuid().ToString('N') + '.jar')
    Copy-Item -LiteralPath $artifact -Destination $runningArtifact
    & $java -jar $runningArtifact @options
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
