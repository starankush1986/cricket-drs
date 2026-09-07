# Deploy web + Android update files. Uses saved Host cricketdrs.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

ssh -o BatchMode=yes cricketdrs "mkdir -p /var/www/cricket-drs/audio /var/www/cricket-drs/downloads"
scp -o BatchMode=yes server.js sender.html receiver.html package.json package-lock.json favicon.png cricketdrs:/var/www/cricket-drs/
if (Test-Path "audio") {
    scp -o BatchMode=yes audio/*.ogg cricketdrs:/var/www/cricket-drs/audio/
}
if (Test-Path "downloads\version.json") {
    scp -o BatchMode=yes downloads\version.json cricketdrs:/var/www/cricket-drs/downloads/version.json
}

$apk = Get-ChildItem "android-sender\app\build\outputs\apk\debug\app-debug.apk","android-sender\app\build\outputs\apk\release\app-release.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($apk) {
    scp -o BatchMode=yes $apk.FullName cricketdrs:/var/www/cricket-drs/downloads/cricket-drs.apk
    Write-Host "Uploaded APK $($apk.FullName)"
}

ssh -o BatchMode=yes cricketdrs "cd /var/www/cricket-drs && npm ci --omit=dev && pm2 restart cricket-drs"
Write-Host "Deployed. Open https://cricketdrs.com"
