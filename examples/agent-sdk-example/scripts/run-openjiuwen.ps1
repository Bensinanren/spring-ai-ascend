param(
    [ValidateSet("react", "deepagent")]
    [string] $Agent = "react"
)

$ErrorActionPreference = "Stop"

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
chcp.com 65001 | Out-Null

$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$requiredJvmOptions = @(
    "-Dfile.encoding=UTF-8",
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8"
)

foreach ($option in $requiredJvmOptions) {
    $propertyName = ($option -replace "=.*$", "")
    if ([string]::IsNullOrWhiteSpace($env:JAVA_TOOL_OPTIONS)) {
        $env:JAVA_TOOL_OPTIONS = $option
    } elseif ($env:JAVA_TOOL_OPTIONS -notlike "*$propertyName*") {
        $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS $option"
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir "..\..\..")
$pom = Join-Path $repoRoot "examples\agent-sdk-example\pom.xml"

if ($Agent -eq "react") {
    $mainClass = "com.huawei.ascend.agentsdk.example.OpenJiuwenReactAgentSdkExample"
} else {
    $mainClass = "com.huawei.ascend.agentsdk.example.OpenJiuwenDeepAgentSdkExample"
}

try {
    mvn -f $pom compile exec:java "-Dexample.mainClass=$mainClass"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}
