$ErrorActionPreference = "Stop"

$projDir = $PSScriptRoot
$jbrBin = "$env:USERPROFILE\AppData\Local\Programs\Android Studio\jbr\bin"
if (Test-Path "C:\Users\clem0\.jdks\openjdk-23.0.2\bin\javac.exe") {
    $jbrBin = "C:\Users\clem0\.jdks\openjdk-23.0.2\bin"
}
$sdkDir = "$env:LOCALAPPDATA\Android\Sdk"
$buildTools = "$sdkDir\build-tools\36.0.0"
$androidJar = "$sdkDir\platforms\android-37.0\android.jar"
$lombokJar = "$projDir\app\libs\lombok.jar"

$buildDir = "$projDir\app\build\manual"
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path "$buildDir\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$buildDir\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$buildDir\dex" | Out-Null
New-Item -ItemType Directory -Force -Path "$projDir\app\build\outputs\apk\debug" | Out-Null

Write-Host "1. Compiling resources with aapt2..."
& "$buildTools\aapt2.exe" compile --dir "$projDir\app\src\main\res" -o "$buildDir\resources.zip"

Write-Host "2. Linking resources..."
& "$buildTools\aapt2.exe" link `
    -I "$androidJar" `
    --manifest "$projDir\app\src\main\AndroidManifest.xml" `
    --java "$buildDir\gen" `
    -o "$buildDir\unaligned.apk" `
    "$buildDir\resources.zip"

Write-Host "3. Compiling Java sources with javac (Java 21 + Lombok)..."
$javaFiles = @()
$javaFiles += Get-ChildItem -Recurse "$projDir\app\src\main\java" -Filter "*.java" | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem -Recurse "$buildDir\gen" -Filter "*.java" | ForEach-Object { $_.FullName }
& "$jbrBin\javac.exe" --release 21 -cp "$androidJar;$lombokJar" -processorpath "$lombokJar" -d "$buildDir\classes" $javaFiles

Write-Host "4. Converting bytecode to DEX with d8..."
$classFiles = Get-ChildItem -Recurse "$buildDir\classes" -Filter "*.class" | ForEach-Object { $_.FullName }
$env:JAVA_HOME = "$env:USERPROFILE\AppData\Local\Programs\Android Studio\jbr"
$env:PATH = "$jbrBin;$env:PATH"
& "$buildTools\d8.bat" --min-api 24 --lib "$androidJar" --output "$buildDir\dex" $classFiles

Write-Host "5. Adding classes.dex and uncompressed assets to APK..."
& "$jbrBin\jar.exe" -uf "$buildDir\unaligned.apk" -C "$buildDir\dex" classes.dex
& "$jbrBin\jar.exe" -u0f "$buildDir\unaligned.apk" -C "$projDir\app\src\main" assets

Write-Host "6. Aligning APK with zipalign..."
$alignedApk = "$buildDir\aligned.apk"
& "$buildTools\zipalign.exe" -f -p 4 "$buildDir\unaligned.apk" "$alignedApk"

Write-Host "7. Signing APK with apksigner..."
$ErrorActionPreference = "Continue"
$keystore = "$projDir\debug.keystore"
if (-not (Test-Path $keystore)) {
    & "$jbrBin\keytool.exe" -genkeypair -keystore "$keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}

$finalApk = "$projDir\app\build\outputs\apk\debug\PanzerGeneral-debug.apk"
& "$buildTools\apksigner.bat" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --ks "$keystore" --ks-pass pass:android --key-pass pass:android --out "$finalApk" "$alignedApk"
Copy-Item -Force "$finalApk" "$projDir\PanzerGeneral-debug.apk"
& "$buildTools\apksigner.bat" verify --verbose "$finalApk" | Select-Object -First 6

Write-Host "SUCCESS! Built signed APK: $finalApk ($(([Math]::Round((Get-Item $finalApk).Length / 1MB, 2))) MB)"
