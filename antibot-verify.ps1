$ErrorActionPreference = "Stop"

$Instance = "pingbypass-server"
$Zone     = "us-west2-a"
$Opera    = "C:\Users\conng\AppData\Local\Programs\Opera GX\opera.exe"

Write-Host "Opening SSH SOCKS tunnel to $Instance..."
Start-Process -FilePath "cmd.exe" -ArgumentList "/c gcloud compute ssh $Instance --zone=$Zone -- -N -D 127.0.0.1:1080"

Write-Host "Waiting for tunnel to actually accept connections..."
$deadline = (Get-Date).AddSeconds(30)
$up = $false
while ((Get-Date) -lt $deadline) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.Connect("127.0.0.1", 1080)
        $client.Close()
        $up = $true
        break
    } catch {
        Start-Sleep -Milliseconds 500
    }
}
if (-not $up) {
    Write-Host "Tunnel never came up within 30s -- check the PuTTY/gcloud window for errors (host key prompt, auth failure, etc)." -ForegroundColor Red
    exit 1
}
Write-Host "Tunnel is up."

Write-Host "Opening Opera GX through proxy..."
& $Opera --user-data-dir="$env:TEMP\notbot-opera-vps-profile" --proxy-server="socks5://127.0.0.1:1080" "https://api.ipify.org" "https://notbot.es"

Write-Host "Verify IP shows 34.186.139.38 on api.ipify.org, then complete captcha on notbot.es."
Write-Host "Close the PuTTY/gcloud tunnel window when done."
