param(
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { '' }),
    [string]$OutputRoot = ''
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $OutputRoot) { $OutputRoot = Join-Path $ProjectRoot 'dist' }
$ProjectDrive = 'P:'
$SdkDrive = 'S:'
if (Test-Path "$ProjectDrive\" -PathType Container) { throw "$ProjectDrive 已被占用" }
if (Test-Path "$SdkDrive\" -PathType Container) { throw "$SdkDrive 已被占用" }
if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot)) {
    throw '未找到 Android SDK。请设置 ANDROID_SDK_ROOT，或传入 -SdkRoot 参数。'
}
& subst $ProjectDrive $ProjectRoot
if ($LASTEXITCODE -ne 0) { throw '无法映射项目路径' }
try {
    & subst $SdkDrive $SdkRoot
    if ($LASTEXITCODE -ne 0) { throw '无法映射 SDK 路径' }
    $Project = "$ProjectDrive\"
    $Tools = "$SdkDrive\build-tools\35.0.0"
    $AndroidJar = "$SdkDrive\platforms\android-35\android.jar"
    $Build = "$ProjectDrive\build"
    $Gen = "$Build\gen"
    $Classes = "$Build\classes"
    $Dex = "$Build\dex"
    New-Item -ItemType Directory -Force -Path $Build,$Gen,$Classes,$Dex,$OutputRoot | Out-Null

    & "$Tools\aapt2.exe" compile --dir "$ProjectDrive\res" -o "$Build\res.zip"
    if ($LASTEXITCODE -ne 0) { throw '资源编译失败' }
    & "$Tools\aapt2.exe" link -o "$Build\base.apk" -I $AndroidJar `
        --manifest "$ProjectDrive\AndroidManifest.xml" --java $Gen `
        --min-sdk-version 23 --target-sdk-version 32 "$Build\res.zip"
    if ($LASTEXITCODE -ne 0) { throw '资源链接失败' }
    $Sources = @(Get-ChildItem "$ProjectDrive\src" -Recurse -Filter '*.java' | ForEach-Object FullName)
    $Sources += @(Get-ChildItem $Gen -Recurse -Filter '*.java' | ForEach-Object FullName)
    & javac -encoding UTF-8 --release 8 -classpath $AndroidJar -d $Classes $Sources
    if ($LASTEXITCODE -ne 0) { throw 'Java 编译失败' }
    & jar cf "$Build\classes.jar" -C $Classes .
    if ($LASTEXITCODE -ne 0) { throw '类归档失败' }
    & "$Tools\d8.bat" --lib $AndroidJar --min-api 23 --output $Dex "$Build\classes.jar"
    if ($LASTEXITCODE -ne 0) { throw 'Dex 编译失败' }
    Copy-Item "$Build\base.apk" "$Build\unsigned.apk" -Force
    & jar uf "$Build\unsigned.apk" -C $Dex classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'APK 打包失败' }
    & "$Tools\zipalign.exe" -f 4 "$Build\unsigned.apk" "$Build\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK 对齐失败' }

    $Keystore = "$Build\pixel-whale-debug.keystore"
    if (-not (Test-Path $Keystore)) {
        & keytool -genkeypair -keystore $Keystore -storepass android -keypass android `
            -alias pixelwhale -dname 'CN=Pixel Whale,O=Local Build,C=CN' `
            -keyalg RSA -keysize 2048 -validity 3650
        if ($LASTEXITCODE -ne 0) { throw '签名密钥创建失败' }
    }
    $Apk = Join-Path $OutputRoot 'pixel-whale-deepseek-2.5.apk'
    $SignedApk = "$Build\signed.apk"
    & "$Tools\apksigner.bat" sign --ks $Keystore --ks-key-alias pixelwhale `
        --ks-pass pass:android --key-pass pass:android --out $SignedApk "$Build\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名失败' }
    & "$Tools\apksigner.bat" verify --verbose $SignedApk
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败' }
    Copy-Item $SignedApk $Apk -Force
    Write-Output $Apk
} finally {
    if (Test-Path "$SdkDrive\" -PathType Container) { & subst $SdkDrive /D }
    & subst $ProjectDrive /D
}
