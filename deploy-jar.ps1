$ErrorActionPreference = "Stop"

$Instance  = "pingbypass-server"
$Zone      = "us-west2-a"
$RemoteUser = "SonHoang"
$JarLocal  = "$PSScriptRoot\build\libs\euclient-0.0.1.jar"
$JarRemote = "/home/$RemoteUser/euclient-0.0.1.jar"

Write-Host "Building..."
& "$PSScriptRoot\gradlew.bat" build -x test -q

Write-Host "Uploading jar to VPS..."
gcloud compute scp "$JarLocal" "${Instance}:${JarRemote}" --zone=$Zone

Write-Host "Updating mod jar in container and restarting..."
gcloud compute ssh $Instance --zone=$Zone --command="sudo docker cp $JarRemote pingbypass-server:/mc-stash/astera.jar && sudo docker restart pingbypass-server"

Write-Host "Done. New jar is live after container restart."
