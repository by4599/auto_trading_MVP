<#
.SYNOPSIS
    거래 DB·캔들 DB를 압축 백업해 이 컴퓨터 밖(Supabase Storage)으로 내보낸다.

.DESCRIPTION
    왜 필요한가:
      trading-db(거래 기록·분봉)와 backtest-db(캔들)는 이 컴퓨터에만 있다.
      특히 분봉은 증권사가 과거를 소급 제공하지 않아 파일이 날아가면 영구 손실이다.
      기존 backup.sh는 같은 디스크의 backup/ 폴더에 복사할 뿐이라 디스크 고장에 무력하다.

    앱을 멈추지 않는다:
      H2의 BACKUP TO 명령으로 온라인 스냅샷을 뜬다. 앱이 쓰는 도중이어도 정합성이
      보장된 zip이 만들어진다 — 단순 파일 복사와 다른 점이다. 실측 18MB -> 4.5MB.

    환경변수 SUPABASE_URL / SUPABASE_KEY(서버 전용 secret key)가 없으면 업로드를
    건너뛰고 로컬 zip만 남긴다. 실패해도 매매 앱과는 아무 관계가 없다.

.EXAMPLE
    .\backup-to-supabase.ps1
    .\backup-to-supabase.ps1 -MirrorDir "G:\내 드라이브\trading-backup"
    .\backup-to-supabase.ps1 -SkipUpload
#>
param(
    [string]$Bucket     = "db-backup",
    [int]   $KeepLocal  = 5,
    [int]   $KeepRemote = 7,
    [string]$MirrorDir  = "",
    [switch]$SkipUpload
)

$ErrorActionPreference = "Stop"

$root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$backupDir = Join-Path $root "backup"
$stamp     = Get-Date -Format "yyyyMMdd_HHmmss"
$databases = @("trading-db", "backtest-db")

# 도구 찾기 ------------------------------------------------------------------

function Find-H2Jar {
    $cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.h2database\h2"
    $jar = Get-ChildItem -Path $cache -Filter "h2-*.jar" -Recurse -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*sources*" } |
           Select-Object -First 1
    if (-not $jar) {
        throw "H2 라이브러리를 찾지 못했습니다. 프로젝트를 한 번 빌드한 뒤 다시 실행하세요."
    }
    return $jar.FullName
}

# 백업 ------------------------------------------------------------------------

function Backup-Database {
    param([string]$DbName, [string]$H2Jar)

    $dbPath = Join-Path $root $DbName
    if (-not (Test-Path "$dbPath.mv.db")) {
        Write-Host "  - $DbName : 파일이 없어 건너뜁니다"
        return $null
    }

    $dest    = Join-Path $backupDir "$DbName-$stamp.zip"
    $jdbcUrl = "jdbc:h2:file:$($dbPath.Replace('\','/'));AUTO_SERVER=TRUE"
    $sql     = "BACKUP TO '$($dest.Replace('\','/'))'"

    # PowerShell 5.1은 배열 인자로 빈 문자열을 넘기지 못한다(-password "" 가 사라진다)
    # 그래서 인자를 문자열 하나로 만들어 그대로 전달한다.
    # -Xmx128m: 백업은 메모리를 거의 안 쓴다. 기본 힙으로 뜨면 매매 앱과 메모리를 다툰다.
    $argLine = "-Xmx128m -XX:+UseSerialGC -cp `"$H2Jar`" org.h2.tools.Shell " +
               "-url `"$jdbcUrl`" -user sa -password `"`" -sql `"$sql`""
    $errFile = Join-Path $env:TEMP "h2-backup-$DbName.err"

    $proc = Start-Process -FilePath "java" -ArgumentList $argLine -NoNewWindow -Wait -PassThru `
                          -RedirectStandardError $errFile
    if ($proc.ExitCode -ne 0) {
        $detail = if (Test-Path $errFile) { Get-Content $errFile -Raw } else { "(추가 정보 없음)" }
        throw "$DbName 백업 실패 (종료 코드 $($proc.ExitCode))`n$detail"
    }
    return $dest
}

# 보관 개수 정리 (파일명 앞부분으로 DB를 구분해 각각 N개씩 남긴다) -------------

function Remove-OldLocal {
    Get-ChildItem -Path $backupDir -Filter "*.zip" |
        Group-Object { $_.Name -replace '-\d{8}_\d{6}\.zip$', '' } |
        ForEach-Object {
            $_.Group | Sort-Object Name -Descending | Select-Object -Skip $KeepLocal | ForEach-Object {
                Remove-Item $_.FullName -Force
                Write-Host "  - 오래된 로컬 백업 삭제: $($_.Name)"
            }
        }
}

# Supabase Storage ------------------------------------------------------------

function Send-ToSupabase {
    param([string]$File, [hashtable]$Headers, [string]$BaseUrl)

    $name = Split-Path -Leaf $File
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/storage/v1/object/$Bucket/$name" `
                      -Headers $Headers -ContentType "application/zip" -InFile $File | Out-Null
    return $name
}

function Remove-OldRemote {
    param([hashtable]$Headers, [string]$BaseUrl)

    $body  = @{ prefix = ""; limit = 200; sortBy = @{ column = "name"; order = "asc" } } | ConvertTo-Json
    $items = Invoke-RestMethod -Method Post -Uri "$BaseUrl/storage/v1/object/list/$Bucket" `
                               -Headers $Headers -ContentType "application/json" -Body $body

    $items | Where-Object { $_.name -like "*.zip" } |
        Group-Object { $_.name -replace '-\d{8}_\d{6}\.zip$', '' } |
        ForEach-Object {
            $_.Group | Sort-Object name -Descending | Select-Object -Skip $KeepRemote | ForEach-Object {
                Invoke-RestMethod -Method Delete -Uri "$BaseUrl/storage/v1/object/$Bucket/$($_.name)" `
                                  -Headers $Headers | Out-Null
                Write-Host "  - 오래된 원격 백업 삭제: $($_.name)"
            }
        }
}

# 실행 ------------------------------------------------------------------------

Write-Host "[백업] 시작 - $stamp"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$h2Jar = Find-H2Jar

$created = @()
foreach ($db in $databases) {
    $zip = Backup-Database -DbName $db -H2Jar $h2Jar
    if ($zip) {
        $sizeMb = (Get-Item $zip).Length / 1MB
        Write-Host ("  + {0} ({1:N1} MB)" -f (Split-Path -Leaf $zip), $sizeMb)
        $created += $zip
    }
}
if ($created.Count -eq 0) { throw "백업할 DB 파일을 하나도 찾지 못했습니다." }

Remove-OldLocal

if ($MirrorDir) {
    New-Item -ItemType Directory -Force -Path $MirrorDir | Out-Null
    $created | ForEach-Object { Copy-Item $_ -Destination $MirrorDir -Force }
    Write-Host "  -> 동기화 폴더에도 복사: $MirrorDir"
}

# setx로 저장한 값은 이미 열려 있던 셀에는 반영되지 않는다.
# 프로세스 환경에 없으면 사용자 환경변수에서 한 번 더 찾는다.
function Get-Setting {
    param([string]$Name)
    $v = [Environment]::GetEnvironmentVariable($Name)
    if (-not $v) { $v = [Environment]::GetEnvironmentVariable($Name, 'User') }
    return $v
}

$baseUrl = Get-Setting 'SUPABASE_URL'
$key     = Get-Setting 'SUPABASE_KEY'

if ($SkipUpload) {
    Write-Host "[백업] -SkipUpload 지정 - 업로드하지 않았습니다"
} elseif (-not $baseUrl -or -not $key) {
    Write-Warning "SUPABASE_URL / SUPABASE_KEY 환경변수가 없어 업로드를 건너뜁니다 (로컬 zip은 만들어졌습니다)"
} else {
    $headers = @{ apikey = $key; Authorization = "Bearer $key" }
    $baseUrl = $baseUrl.TrimEnd('/')
    try {
        foreach ($zip in $created) {
            $name = Send-ToSupabase -File $zip -Headers $headers -BaseUrl $baseUrl
            Write-Host "  ^ 업로드 완료: $name"
        }
        Remove-OldRemote -Headers $headers -BaseUrl $baseUrl
    } catch {
        # 서버가 보낸 설명(JSON)을 꺼내야 원인을 안다 - 버킷 없음 / 키 권한 부족은 메시지가 다르다
        $detail = $_.ErrorDetails.Message
        if (-not $detail -and $_.Exception.Response) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $reader.ReadToEnd()
        }
        Write-Warning "업로드 실패: $($_.Exception.Message)"
        if ($detail) { Write-Warning "서버 응답: $detail" }
        Write-Warning "버킷 '$Bucket'이 없으면 docs/supabase-schema.sql의 버킷 생성 SQL을 먼저 실행하세요."
        exit 2
    }
}

Write-Host "[백업] 끝 - 로컬 보관 ${KeepLocal}개 / 원격 보관 ${KeepRemote}개"
