# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File count-code.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File count-code.ps1 -IncludeVendor
#   powershell -NoProfile -ExecutionPolicy Bypass -File count-code.ps1 -IncludeDocs
# 也可以直接运行 count-code.cmd。
param(
    [switch]$IncludeVendor,
    [switch]$IncludeDocs
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $scriptDir '..')).Path
$rootPrefix = $root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar

# 默认排除构建产物、依赖目录、日志和第三方压缩资源，避免把 node_modules、
# dist、target 里的内容算进项目代码量。
$excludeDirNames = @(
    '.git', 'node_modules', 'dist', 'target', 'logs',
    '.idea', '.vscode', '.mvn', 'demo-screenshot'
)

$vendorPrefixes = @(
    'renren-fast-vue/static/plugins/',
    'renren-fast-vue/src/element-ui/',
    'renren-fast-vue/src/element-ui-theme/'
)

$vendorPathPatterns = @(
    '(?i)/(bootstrap|easyui|jquery|libs|plugins|font-awesome(?:-[^/]+)?|ueditor|element-ui|element-ui-theme|vendor|swagger)/'
)

$vendorFileNamePatterns = @(
    '(?i)^(jquery|jquery-ui|bootstrap|swiper|zepto|echarts|ueditor|easyui|layui|element-ui|font-awesome|iconfont)'
)

$categoryOrder = @(
    'Backend main',
    'Backend tests',
    'Frontend main',
    'Frontend tests',
    'SQL',
    'Config',
    'Scripts',
    'Templates/Other',
    'Docs'
)

$categoryStats = @{}
foreach ($category in $categoryOrder) {
    $categoryStats[$category] = [pscustomobject]@{
        Files    = 0
        Lines    = 0
        NonEmpty = 0
    }
}

$moduleStats = @{}

function Get-Category {
    param(
        [string]$RelativePath,
        [string]$Extension,
        [string]$FileName
    )

    if ($Extension -eq '.java' -or $Extension -eq '.kt') {
        if ($RelativePath -match '(^|/)src/test/') {
            return 'Backend tests'
        }
        return 'Backend main'
    }

    if ($Extension -eq '.sql') {
        return 'SQL'
    }

    if ($Extension -eq '.jmx') {
        return 'Scripts'
    }

    if ($Extension -in @('.vue', '.js', '.jsx', '.ts', '.tsx',
            '.html', '.htm', '.css', '.scss', '.sass', '.less')) {
        if ($RelativePath -match '(^|/)(test|tests)/') {
            return 'Frontend tests'
        }
        return 'Frontend main'
    }

    if ($Extension -eq '.xml') {
        if ($RelativePath -match '(^|/)mapper/') {
            return 'SQL'
        }
        return 'Config'
    }

    if ($Extension -in @('.yml', '.yaml', '.properties', '.json', '.conf',
            '.ini', '.toml', '.gitignore', '.gitattributes', '.editorconfig')) {
        return 'Config'
    }

    if ($FileName -in @('Dockerfile', 'Makefile', 'Jenkinsfile',
            '.gitignore', '.gitattributes', '.editorconfig', '.dockerignore')) {
        return 'Config'
    }

    if ($FileName -like '.env*') {
        return 'Config'
    }

    if ($Extension -in @('.sh', '.cmd', '.bat', '.ps1')) {
        return 'Scripts'
    }

    if ($Extension -in @('.vm', '.ftl', '.jsp')) {
        return 'Templates/Other'
    }

    if ($Extension -eq '.md') {
        return 'Docs'
    }

    return $null
}

Write-Host ''
Write-Host 'Gulimall project code statistics'
Write-Host ("Root: {0}" -f $root)
Write-Host ("Include vendor: {0}; include docs: {1}" -f $IncludeVendor.IsPresent, $IncludeDocs.IsPresent)
Write-Host ("Excluded directories: {0}" -f ($excludeDirNames -join ', '))
if (-not $IncludeVendor) {
    Write-Host ("Excluded vendor paths: {0}" -f ($vendorPrefixes -join ', '))
    Write-Host 'Excluded vendor path/filename patterns: bootstrap, easyUI, jQuery, libs, plugins, font-awesome, ueditor, element-ui, swagger, swiper, zepto, echarts, iconfont, *.min.js, *.min.css'
}
if (-not $IncludeDocs) {
    Write-Host 'Excluded docs: *.md, document/, memory/'
}
Write-Host ''

$files = Get-ChildItem -LiteralPath $root -Recurse -File -Force -ErrorAction SilentlyContinue

foreach ($file in $files) {
    $relativePath = $file.FullName.Substring($rootPrefix.Length).Replace('\', '/')
    $segments = $relativePath.Split('/')

    $skip = $false
    foreach ($segment in $segments) {
        if ($excludeDirNames -contains $segment) {
            $skip = $true
            break
        }
    }
    if ($skip) {
        continue
    }

    if (-not $IncludeVendor) {
        foreach ($prefix in $vendorPrefixes) {
            if ($relativePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
                $skip = $true
                break
            }
        }
        foreach ($pattern in $vendorPathPatterns) {
            if ($relativePath -match $pattern) {
                $skip = $true
                break
            }
        }
        if (-not $skip) {
            foreach ($pattern in $vendorFileNamePatterns) {
                if ($file.Name -match $pattern) {
                    $skip = $true
                    break
                }
            }
        }
        if ($skip) {
            continue
        }
    }

    if (-not $IncludeDocs) {
        if ($relativePath.StartsWith('document/', [StringComparison]::OrdinalIgnoreCase) -or
            $relativePath.StartsWith('memory/', [StringComparison]::OrdinalIgnoreCase) -or
            $file.Extension.ToLowerInvariant() -eq '.md') {
            continue
        }
    }

    $fileName = $file.Name
    $extension = [IO.Path]::GetExtension($fileName).ToLowerInvariant()

    # 锁文件和 source map 不是项目代码；压缩文件默认也跳过。
    if ($fileName -match '(?i)^(package-lock\.json|yarn\.lock|pnpm-lock\.yaml)$') {
        continue
    }
    if ($extension -eq '.map') {
        continue
    }
    if (-not $IncludeVendor -and $fileName -match '(?i)\.min\.(js|css)$') {
        continue
    }

    $category = Get-Category -RelativePath $relativePath -Extension $extension -FileName $fileName
    if ($null -eq $category) {
        continue
    }

    try {
        $lines = [IO.File]::ReadAllLines($file.FullName)
    } catch {
        Write-Warning ("Skip unreadable file: {0} ({1})" -f $relativePath, $_.Exception.Message)
        continue
    }

    $totalLines = $lines.Length
    $nonEmptyLines = 0
    foreach ($line in $lines) {
        if ($line.Trim().Length -gt 0) {
            $nonEmptyLines++
        }
    }

    $categoryStats[$category].Files++
    $categoryStats[$category].Lines += $totalLines
    $categoryStats[$category].NonEmpty += $nonEmptyLines

    if ($relativePath.Contains('/')) {
        $module = $relativePath.Split('/')[0]
    } else {
        $module = '(root)'
    }
    if (-not $moduleStats.ContainsKey($module)) {
        $moduleStats[$module] = [pscustomobject]@{
            Files    = 0
            Lines    = 0
            NonEmpty = 0
        }
    }
    $moduleStats[$module].Files++
    $moduleStats[$module].Lines += $totalLines
    $moduleStats[$module].NonEmpty += $nonEmptyLines
}

$categoryFormat = "{0,-18} {1,8} {2,14} {3,14}"
Write-Host 'Code volume by category'
Write-Host ($categoryFormat -f 'Category', 'Files', 'Total lines', 'Non-empty lines')
Write-Host ("-" * 60)

$totalFiles = 0
$totalLines = 0
$totalNonEmpty = 0

foreach ($category in $categoryOrder) {
    $stats = $categoryStats[$category]
    if ($stats.Files -eq 0) {
        continue
    }
    Write-Host ($categoryFormat -f $category, $stats.Files, $stats.Lines, $stats.NonEmpty)
    $totalFiles += $stats.Files
    $totalLines += $stats.Lines
    $totalNonEmpty += $stats.NonEmpty
}

Write-Host ("-" * 60)
Write-Host ($categoryFormat -f 'TOTAL', $totalFiles, $totalLines, $totalNonEmpty)
Write-Host ''

$moduleFormat = "{0,-32} {1,8} {2,14} {3,14}"
Write-Host 'Code volume by top-level module'
Write-Host ($moduleFormat -f 'Module', 'Files', 'Total lines', 'Non-empty lines')
Write-Host ("-" * 74)

$moduleNames = @($moduleStats.Keys | Sort-Object)
foreach ($module in $moduleNames) {
    $stats = $moduleStats[$module]
    Write-Host ($moduleFormat -f $module, $stats.Files, $stats.Lines, $stats.NonEmpty)
}

Write-Host ''
