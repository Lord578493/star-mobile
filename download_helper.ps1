param(
    [string]$Url,
    [string]$Out,
    [string]$Name,
    [int]$MinMB = 0
)

if (Test-Path $Out) {
    $existSize = [math]::Round((Get-Item $Out).Length / 1MB, 1)
    if ($existSize -ge $MinMB) {
        Write-Host "  [OK] $Name already downloaded ($existSize MB)"
        exit 0
    } else {
        Write-Host "  [!] File incomplete ($existSize MB < $MinMB MB), re-downloading..."
        Remove-Item $Out -Force
    }
}

Write-Host "  Downloading: $Name..."

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

    $req = [Net.HttpWebRequest]::Create($Url)
    $req.Timeout = 60000
    $req.ReadWriteTimeout = 120000
    $resp = $req.GetResponse()
    $total = $resp.ContentLength
    $stream = $resp.GetResponseStream()

    $dir = Split-Path $Out -Parent
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }

    $file = [IO.File]::Create($Out)
    $buf = New-Object byte[] 65536
    $downloaded = 0
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $lastUpdate = 0

    while ($true) {
        $read = $stream.Read($buf, 0, $buf.Length)
        if ($read -eq 0) { break }
        $file.Write($buf, 0, $read)
        $downloaded += $read
        $now = $sw.ElapsedMilliseconds
        if (($now - $lastUpdate) -gt 400) {
            $lastUpdate = $now
            $pct = 0
            if ($total -gt 0) { $pct = [int]($downloaded * 100 / $total) }
            $mb = [math]::Round($downloaded / 1MB, 1)
            $tot = "?"
            if ($total -gt 0) { $tot = [math]::Round($total / 1MB, 0) }
            $spd = 0
            if ($now -gt 1000) { $spd = [math]::Round($downloaded / 1KB / ($now / 1000), 0) }
            $filled = [int]($pct / 5)
            $bar = ("=" * $filled) + ("." * (20 - $filled))
            Write-Host ("`r  [$bar] $pct%  $mb/$tot MB  $spd KB/s     ") -NoNewline
        }
    }

    $file.Close()
    $stream.Close()
    $resp.Close()

    $finalMB = [math]::Round($downloaded / 1MB, 1)
    Write-Host "`r  [====================] 100%  $finalMB MB  Done!                    "

    $checkSize = [math]::Round((Get-Item $Out).Length / 1MB, 1)
    if ($checkSize -lt $MinMB) {
        Write-Host "  ERROR: File incomplete after download ($checkSize MB < $MinMB MB)"
        Remove-Item $Out -Force
        exit 1
    }

    exit 0

} catch {
    $msg = $_.Exception.Message
    Write-Host "`n  ERROR: $msg"
    if (Test-Path $Out) { Remove-Item $Out -Force }
    exit 1
}
