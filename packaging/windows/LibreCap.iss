#define MyAppName "LibreCap"
#define MyAppVersion "0.1.0"
#define MyAppPublisher "LibreCap contributors"
#define MyAppExeName "LibreCap.exe"

[Setup]
AppId={{B5F2A98A-7A75-4C86-8F0A-6F39F9C7C2A0}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\LibreCap
DefaultGroupName={#MyAppName}
OutputDir=..\..\dist\installer
OutputBaseFilename=LibreCap-Setup
Compression=lzma
SolidCompression=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#MyAppName}

[Files]
Source: "..\..\dist\LibreCap\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent
