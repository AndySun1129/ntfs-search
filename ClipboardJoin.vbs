Option Explicit

' ==================== 設定可能な定数 ====================
Const GROUP_ROWS = 25      ' グループあたりの行数（変更可能）
Const COL_COUNT = 132      ' 1行あたりの列数（変更可能）
' =======================================================

Dim strData, arrLines
Dim i, j, rowCount, groupCount
Dim outputLines(), outputText
Dim shell, fso, ts, tempFile, psFile

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
tempFile = fso.GetSpecialFolder(2) & "\clip_data.txt"
psFile = fso.GetSpecialFolder(2) & "\clip_read.ps1"

' ====== PowerShellを使用してクリップボードから読み取り ======
' PowerShell読み取りスクリプトを作成
Set ts = fso.CreateTextFile(psFile, True, True)
ts.WriteLine "$data = Get-Clipboard"
ts.WriteLine "$data | Out-File -FilePath '" & tempFile & "' -Encoding Unicode"
ts.Close

' PowerShellスクリプトを実行
shell.Run "powershell -ExecutionPolicy Bypass -File """ & psFile & """", 0, True
WScript.Sleep 1000

' スクリプトファイルを削除
If fso.FileExists(psFile) Then fso.DeleteFile psFile

' 一時ファイルを読み取り
If fso.FileExists(tempFile) Then
    Set ts = fso.OpenTextFile(tempFile, 1, False, True)
    strData = ts.ReadAll
    ts.Close
    fso.DeleteFile tempFile
Else
    MsgBox "クリップボードの読み取りに失敗しました。テキストデータがコピーされていることを確認してください。", vbExclamation, "エラー"
    WScript.Quit
End If

If Trim(strData) = "" Then
    MsgBox "クリップボードが空です。テキストデータをコピーしてから実行してください。", vbExclamation, "エラー"
    WScript.Quit
End If

' ====== 行ごとに分割 ======
arrLines = Split(strData, vbNewLine)
rowCount = UBound(arrLines) + 1

' 末尾の空行を削除
Do While rowCount > 0 And Trim(arrLines(rowCount - 1)) = ""
    rowCount = rowCount - 1
    ReDim Preserve arrLines(rowCount - 1)
Loop

If rowCount = 0 Then
    MsgBox "有効なデータがありません。", vbExclamation, "エラー"
    WScript.Quit
End If

' ====== 行数を検証（GROUP_ROWS の倍数であること）======
If rowCount Mod GROUP_ROWS <> 0 Then
    MsgBox "行数(" & rowCount & ")が" & GROUP_ROWS & "の倍数ではありません。" & vbCrLf & _
           "データの行数が" & GROUP_ROWS & "の倍数であることを確認してください。", vbExclamation, "エラー"
    WScript.Quit
End If

groupCount = rowCount \ GROUP_ROWS

' ====== 出力配列を初期化 ======
ReDim outputLines(GROUP_ROWS - 1)
For i = 0 To GROUP_ROWS - 1
    outputLines(i) = ""
Next

' ====== 各グループを処理 ======
For i = 0 To groupCount - 1
    Dim baseIndex
    baseIndex = i * GROUP_ROWS
    
    For j = 0 To GROUP_ROWS - 1
        Dim currentLine
        currentLine = arrLines(baseIndex + j)
        
        ' 行を COL_COUNT 列に揃える（不足はスペースで埋め、超過は切り捨て）
        currentLine = PadLine(currentLine, COL_COUNT)
        
        outputLines(j) = outputLines(j) & currentLine
    Next
Next

outputText = Join(outputLines, vbNewLine)

' ====== クリップボードに書き込み ======
Set ts = fso.CreateTextFile(tempFile, True, True)
ts.Write outputText
ts.Close

' PowerShellを使用してクリップボードに書き込み
psFile = fso.GetSpecialFolder(2) & "\clip_write.ps1"
Set ts = fso.CreateTextFile(psFile, True, True)
ts.WriteLine "$text = Get-Content -Path '" & tempFile & "' -Encoding Unicode"
ts.WriteLine "$text | Set-Clipboard"
ts.Close

shell.Run "powershell -ExecutionPolicy Bypass -File """ & psFile & """", 0, True
WScript.Sleep 1000

' 一時ファイルを削除
If fso.FileExists(tempFile) Then fso.DeleteFile tempFile
If fso.FileExists(psFile) Then fso.DeleteFile psFile

MsgBox "連結が完了しました！" & vbCrLf & _
       "元の行数: " & rowCount & vbCrLf & _
       "グループあたりの行数: " & GROUP_ROWS & vbCrLf & _
       "グループ数: " & groupCount & vbCrLf & _
       "1行あたりの列数: " & COL_COUNT & vbCrLf & _
       "出力行数: " & GROUP_ROWS, vbInformation, "完了"

' ============================================================
' 行を指定された列数に揃える（補完または切り捨て）
' ============================================================
Function PadLine(ByVal line, ByVal colCount)
    ' 行の長さが列数より短い場合、スペースで埋める
    If Len(line) < colCount Then
        PadLine = line & String(colCount - Len(line), " ")
    Else
        ' 行の長さが列数より長い場合、切り捨てる
        PadLine = Left(line, colCount)
    End If
End Function