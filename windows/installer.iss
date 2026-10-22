#ifndef SourceDir
  #define SourceDir "publish"
#endif
#ifndef OutputPath
  #define OutputPath "installer-output"
#endif
[Setup]
AppId={{A84C74E6-A779-4D3E-AD1F-C0D4CD50F372}
AppName=TermoDom
AppVersion=2.0.0
AppVerName=TermoDom 2.0.0
AppPublisher=TermoDom
AppPublisherURL=https://github.com/leo25matko-boop/termo
AppSupportURL=https://github.com/leo25matko-boop/termo/issues
DefaultDirName={localappdata}\Programs\TermoDom Native
DefaultGroupName=TermoDom
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir={#OutputPath}
OutputBaseFilename=TermoDom-Setup-2.0.0-x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
UninstallDisplayIcon={app}\TermoDom.exe
SetupIconFile=Assets\icon.ico
CloseApplications=yes
RestartApplications=no
DisableWelcomePage=no
SetupMutex=TermoDomNativeSetup

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\TermoDom"; Filename: "{app}\TermoDom.exe"
Name: "{autodesktop}\TermoDom"; Filename: "{app}\TermoDom.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\TermoDom.exe"; Description: "Launch TermoDom"; Flags: nowait postinstall skipifsilent
