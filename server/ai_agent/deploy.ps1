$ServerIP = "YOUR_SERVER_IP"
$KeyPath = "C:\PATH\TO\YOUR_KEY.pem"
$RemoteUser = "ubuntu"
$LocalPath = "C:\PATH\TO\suguna-rtc\server\ai_agent"
$ErrorActionPreference = "Stop"

Write-Host "Starting Deployment..." -ForegroundColor Cyan

# 1. Fix Permissions (Safe Try)
try {
    $acl = Get-Acl $KeyPath
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($env:USERNAME, "FullControl", "Allow")
    $acl.SetAccessRule($rule)
    Set-Acl $KeyPath $acl
} catch {
    Write-Host "Skipping permission fix (might already be okay)..." -ForegroundColor Yellow
}

# 2. Upload Files
Write-Host "Uploading Agent Files..."
$files = @("agent.py", "requirements.txt", "setup_remote.sh")
foreach ($file in $files) {
    if (Test-Path "$LocalPath\$file") {
        scp -i "$KeyPath" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "$LocalPath\$file" "$RemoteUser@${ServerIP}:~/"
    }
}

# 3. Remote Setup & Start
Write-Host "Running Remote Setup & Starting Agent..."
ssh -i "$KeyPath" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "$RemoteUser@$ServerIP" "sed -i 's/\r$//' setup_remote.sh && chmod +x setup_remote.sh && ./setup_remote.sh"

Write-Host "✅ Deployment Complete! Agent is Ready." -ForegroundColor Green
Write-Host "To Login and Check: ssh -i `"$KeyPath`" $RemoteUser@$ServerIP"
