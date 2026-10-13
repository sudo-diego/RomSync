@echo off
rem Preparar el PC para RomSync: activa el servidor SFTP (OpenSSH) que trae Windows.
rem Se abre como administrador solo; despues muestra la IP y el usuario para la app.
net session >nul 2>&1 || (powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0'" & exit /b)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$t = Get-Content -LiteralPath '%~f0' -Encoding UTF8; $i = [array]::IndexOf($t, '#PS'); iex ($t[($i + 1)..($t.Count - 1)] -join [char]10)"
pause
exit /b
#PS
$ErrorActionPreference = 'Stop'
Write-Host ''
Write-Host '  RomSync - preparar el PC' -ForegroundColor Cyan
Write-Host ''
try {
    $cap = Get-WindowsCapability -Online -Name 'OpenSSH.Server*' | Select-Object -First 1
    if ($cap.State -ne 'Installed') {
        Write-Host '  Instalando el servidor OpenSSH (puede tardar unos minutos)...'
        Add-WindowsCapability -Online -Name $cap.Name | Out-Null
    } else {
        Write-Host '  El servidor OpenSSH ya estaba instalado.'
    }
    Set-Service -Name sshd -StartupType Automatic
    Start-Service sshd
    Write-Host '  Servidor encendido (y se enciende solo al prender el PC).'
    if (-not (Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH (RomSync)' -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 | Out-Null
    } else {
        Enable-NetFirewallRule -Name 'OpenSSH-Server-In-TCP'
    }
    Write-Host '  Firewall: listo.'
    Write-Host ''
    Write-Host '  Listo. En RomSync (Ajustes -> PC) escribe:' -ForegroundColor Green
    Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -match '^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)' } |
        ForEach-Object { Write-Host ('    IP del PC:  ' + $_.IPAddress) }
    Write-Host ('    Usuario:    ' + $env:USERNAME)
    Write-Host '    Contrasena: la de tu cuenta de Windows (si usas cuenta de Microsoft, la de esa cuenta; no el PIN)'
} catch {
    Write-Host ''
    Write-Host ('  No se pudo: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host '  Alternativa: Configuracion -> Sistema -> Caracteristicas opcionales -> Servidor OpenSSH.'
}
Write-Host ''
