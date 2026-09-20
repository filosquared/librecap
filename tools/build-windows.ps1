$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Python = if ($env:PYTHON_BIN) { $env:PYTHON_BIN } else { "python" }

& $Python -c "import PyInstaller, webview"
if ($LASTEXITCODE -ne 0) {
	throw "PyInstaller and pywebview are required. Install requirements-windows.txt first."
}

$PyInstallerArgs = @(
	"-m", "PyInstaller",
	"--noconfirm",
	"--clean",
	"--windowed",
	"--name", "LibreCap",
	"--paths", $RootDir,
	"--add-data", "$(Join-Path $RootDir 'html');html",
	"--add-data", "$(Join-Path $RootDir 'static');static",
	"--collect-all", "webview",
	"--distpath", (Join-Path $RootDir "dist"),
	"--workpath", (Join-Path $RootDir "build\windows"),
	"--specpath", (Join-Path $RootDir "build\windows"),
	(Join-Path $RootDir "desktop_app.py")
)

& $Python @PyInstallerArgs
if ($LASTEXITCODE -ne 0) {
	exit $LASTEXITCODE
}

Write-Host "Built $(Join-Path $RootDir 'dist\LibreCap\LibreCap.exe')"
