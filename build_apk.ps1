$ErrorActionPreference = "Stop"

$projDir = $PSScriptRoot
Set-Location $projDir

if (-not $env:JAVA_HOME) {
    if (Test-Path "C:\Users\clem0\.jdks\openjdk-23.0.2") {
        $env:JAVA_HOME = "C:\Users\clem0\.jdks\openjdk-23.0.2"
    } elseif (Test-Path "$env:USERPROFILE\AppData\Local\Programs\Android Studio\jbr") {
        $env:JAVA_HOME = "$env:USERPROFILE\AppData\Local\Programs\Android Studio\jbr"
    }
}

Write-Host "Building with Gradle Wrapper (Java 21 + Lombok via Maven Central)..."
& "$projDir\gradlew.bat" assembleDebug

$gradleApk = "$projDir\app\build\outputs\apk\debug\app-debug.apk"
$targetApk = "$projDir\PanzerGeneral-debug.apk"
$downloadsApk = "$env:USERPROFILE\Downloads\PanzerGeneral-debug.apk"

if (Test-Path $gradleApk) {
    Copy-Item -Force $gradleApk $targetApk
    Copy-Item -Force $gradleApk $downloadsApk
    $sizeMb = [Math]::Round((Get-Item $targetApk).Length / 1MB, 2)
    Write-Host "SUCCESS! Built signed APK with Gradle: $targetApk ($sizeMb MB)"
}
