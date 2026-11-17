' AI Dock companion - runs in the background without a console window (for Windows startup).
' Log: %USERPROFILE%\.aidock\companion.log
' If the venv is not ready yet, opens run_companion.bat in a window to install first.
Option Explicit
Dim fso, sh, dir, pyw
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir
pyw = dir & "\.venv\Scripts\pythonw.exe"
If fso.FileExists(pyw) And fso.FileExists(dir & "\.venv\installed-requirements.txt") Then
    sh.Run """" & pyw & """ -m dock_companion", 0, False
Else
    sh.Run """" & dir & "\run_companion.bat""", 1, False
End If
