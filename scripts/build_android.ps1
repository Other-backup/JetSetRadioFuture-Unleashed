param(
    [Parameter(Mandatory = $true)]
    [string]$XbePath,

    [switch]$SkipRecompile
)

$ErrorActionPreference = "Stop"

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$Program,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Step
    )

    Write-Host ""
    Write-Host "==> $Step" -ForegroundColor Cyan
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Step falhou com código $LASTEXITCODE."
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$XbePath = (Resolve-Path $XbePath).Path
if (-not (Test-Path -LiteralPath $XbePath -PathType Leaf)) {
    throw "Arquivo XBE não encontrado: $XbePath"
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    throw "Instale um JDK 17 ou superior e adicione java ao PATH. Android Studio não é necessário."
}
$javaInfo = (& java -version 2>&1 | Out-String)
if ($javaInfo -notmatch 'version "(\d+)') {
    throw "Não consegui identificar a versão do Java."
}
if ([int]$Matches[1] -lt 17) {
    throw "O build precisa de JDK 17 ou superior. Versão detectada: $($Matches[1])."
}

$gradle = Get-Command gradle -ErrorAction SilentlyContinue
if (-not $gradle) {
    throw "Gradle 8.9 ou superior não encontrado no PATH."
}
$gradleInfo = (& gradle --version 2>&1 | Out-String)
if ($gradleInfo -notmatch 'Gradle\s+(\d+\.\d+(?:\.\d+)?)') {
    throw "Não consegui identificar a versão do Gradle."
}
$gradleVersion = [version]$Matches[1]
if ($gradleVersion -lt [version]"8.9") {
    throw "O build precisa de Gradle 8.9 ou superior. Versão detectada: $gradleVersion."
}

$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $sdkRoot) {
    throw "Defina ANDROID_HOME ou ANDROID_SDK_ROOT para a pasta do Android SDK de linha de comando. O Android Studio não é necessário."
}
$sdkRoot = [System.IO.Path]::GetFullPath($sdkRoot)
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
$packages = @(
    "platform-tools",
    "platforms;android-35",
    "build-tools;35.0.0",
    "ndk;27.2.12479018",
    "cmake;3.22.1"
)
if (Test-Path -LiteralPath $sdkManager) {
    Invoke-Checked -Program $sdkManager -Arguments $packages -Step "Instalando/validando pacotes do Android SDK"
} else {
    $requiredPaths = @(
        (Join-Path $sdkRoot "platform-tools\adb.exe"),
        (Join-Path $sdkRoot "platforms\android-35\android.jar"),
        (Join-Path $sdkRoot "build-tools\35.0.0\aapt.exe"),
        (Join-Path $sdkRoot "ndk\27.2.12479018\build\cmake\android.toolchain.cmake"),
        (Join-Path $sdkRoot "cmake\3.22.1\bin\cmake.exe")
    )
    $missing = @($requiredPaths | Where-Object { -not (Test-Path -LiteralPath $_) })
    if ($missing.Count -gt 0) {
        throw "Pacotes do SDK faltando. Instale Android command-line tools em '$sdkRoot\cmdline-tools\latest' e execute sdkmanager $($packages -join ' '), ou instale os pacotes faltantes manualmente."
    }
}

Set-Location $repoRoot
if (-not $SkipRecompile) {
    Write-Host ""
    Write-Host "==> Verificando Capstone" -ForegroundColor Cyan
    & py -3 -c "import capstone" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Invoke-Checked -Program py -Arguments @("-3", "-m", "pip", "install", "--upgrade", "capstone") -Step "Instalando dependência Python Capstone"
    }

    $analysisDir = Join-Path $repoRoot "build-android\analysis"
    $disasmDir = Join-Path $repoRoot "tools\disasm\output"
    $funcIdDir = Join-Path $repoRoot "tools\func_id\output"
    $abiDir = Join-Path $repoRoot "tools\abi_analysis\output"
    $genDir = Join-Path $repoRoot "port\recomp\gen"
    foreach ($directory in @($analysisDir, $disasmDir, $funcIdDir, $abiDir, $genDir)) {
        if (Test-Path -LiteralPath $directory) {
            Remove-Item -LiteralPath $directory -Recurse -Force
        }
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    $analysisJson = Join-Path $analysisDir "jsrf_analysis.json"
    Invoke-Checked -Program py -Arguments @("-3", "-m", "tools.xbe_parser", $XbePath, "--json", $analysisJson) -Step "Lendo cabeçalhos e seções do XBE"
    Invoke-Checked -Program py -Arguments @("-3", "-m", "tools.disasm", $XbePath, "--analysis-json", $analysisJson, "--text-only", "--force", "--output", $disasmDir) -Step "Desmontando o código x86 do jogo"
    Invoke-Checked -Program py -Arguments @("-3", "-m", "tools.func_id", $XbePath, "--output", $funcIdDir) -Step "Identificando funções do XDK e da biblioteca C"
    Invoke-Checked -Program py -Arguments @("-3", "-m", "tools.recomp", $XbePath, "--all", "--split", "1000", "--exclude-manual", "port/recomp_manual.c", "--gen-dir", $genDir) -Step "Gerando os arquivos C nativos do jogo"

    if (-not (Test-Path -LiteralPath (Join-Path $genDir "recomp_types.h"))) {
        throw "O recompiler terminou sem criar recomp_types.h; nenhum APK de jogo será empacotado."
    }
} elseif (-not (Test-Path -LiteralPath (Join-Path $repoRoot "port\recomp\gen\recomp_types.h"))) {
    throw "-SkipRecompile foi usado, mas port\recomp\gen\recomp_types.h não existe."
}

$androidDir = Join-Path $repoRoot "android"
Push-Location $androidDir
try {
    Invoke-Checked -Program gradle -Arguments @("--no-daemon", "assembleDebug") -Step "Compilando o APK ARM64"
} finally {
    Pop-Location
}

$apkPath = Join-Path $repoRoot "android\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
    throw "O Gradle terminou, mas não encontrei o APK esperado em $apkPath"
}
$outputDir = Join-Path $repoRoot "build-android"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$outputPath = Join-Path $outputDir "JSRF-Unleashed-Android-debug.apk"
Copy-Item -LiteralPath $apkPath -Destination $outputPath -Force

Write-Host ""
Write-Host "APK criado:" -ForegroundColor Green
Write-Host $outputPath -ForegroundColor Green
Write-Host "Instale no celular para testar. O suporte gráfico/runtime ainda é experimental." -ForegroundColor Yellow
