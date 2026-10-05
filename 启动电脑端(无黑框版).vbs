Set fso = CreateObject("Scripting.FileSystemObject")
currentDir = fso.GetParentFolderName(WScript.ScriptFullName)
Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = currentDir & "\bin\CrossTransfer_PC"
WshShell.Run """" & currentDir & "\bin\CrossTransfer_PC\CrossTransfer_PC.exe""", 0, False
WScript.Sleep 1500
WshShell.Run "http://127.0.0.1:52020", 1, False
