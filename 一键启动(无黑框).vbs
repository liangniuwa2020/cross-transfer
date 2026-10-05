Set fso = CreateObject("Scripting.FileSystemObject")
currentDir = fso.GetParentFolderName(WScript.ScriptFullName)
Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = currentDir & "\bin\CrossTransfer_PC"
WshShell.Run """" & currentDir & "\bin\CrossTransfer_PC\CrossTransfer_PC.exe""", 0, False