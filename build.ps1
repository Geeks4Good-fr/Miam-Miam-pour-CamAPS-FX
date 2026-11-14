# Reconstruit l'APK signé de Meal Announcer et affiche son empreinte.
# Usage :  .\build.ps1                 (release signée)
#          .\build.ps1 -DebugOutput    (variante debug, identifiant .debug)
#          .\build.ps1 -SkipTests      (ne lance pas les tests du domaine)
param(
    [switch]$DebugOutput,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root
try {
    if (-not (Test-Path "$root\keystore.properties")) {
        throw "keystore.properties manquant : impossible de signer l'APK release."
    }
    if (-not (Test-Path "$root\mealannouncer.jks")) {
        throw "mealannouncer.jks manquant : impossible de signer l'APK release."
    }

    if (-not $SkipTests) {
        & .\gradlew.bat :core:model:test
        if ($LASTEXITCODE -ne 0) { throw "Les tests du domaine ont échoué (code $LASTEXITCODE)." }
    }

    if ($DebugOutput) {
        & .\gradlew.bat :app:assembleDebug
    } else {
        & .\gradlew.bat :app:assembleRelease
    }
    if ($LASTEXITCODE -ne 0) { throw "La compilation a échoué (code $LASTEXITCODE)." }

    $apks = Get-ChildItem "$root\app\build\outputs\apk" -Recurse -Filter *.apk |
        Sort-Object LastWriteTime -Descending
    if (-not $apks) { throw "Aucun APK produit." }

    foreach ($apk in $apks) {
        $hash = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash
        "{0}`n  {1:N2} Mo`n  SHA-256 {2}`n" -f $apk.FullName, ($apk.Length / 1MB), $hash
    }
}
finally {
    Pop-Location
}
