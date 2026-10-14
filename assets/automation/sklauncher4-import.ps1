# ASCENDERSMC - Asistente de importacion SKLauncher 4.x
# Solo usa interacciones publicas de la interfaz. Nunca altera el estado interno del launcher.
param(
  [Parameter(Mandatory=$true)][string]$Launcher,
  [Parameter(Mandatory=$true)][string]$Modpack,
  [Parameter(Mandatory=$true)][string]$Instances,
  [Parameter(Mandatory=$true)][string]$Marker,
  [Parameter(Mandatory=$true)][string]$MarkerValue,
  [int]$TimeoutSeconds = 105
)
$ErrorActionPreference = 'Stop'
function Report([string]$message) { [Console]::Out.WriteLine("SK4AUTO $message") }
if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) { Report 'launcher.not-found'; exit 3 }
if (-not (Test-Path -LiteralPath $Modpack -PathType Leaf)) { Report 'mrpack.not-found'; exit 3 }
if (-not (Test-Path -LiteralPath $Instances -PathType Container)) { New-Item -Force -ItemType Directory -Path $Instances | Out-Null }
function FindImported {
  foreach ($d in (Get-ChildItem -LiteralPath $Instances -Directory -ErrorAction SilentlyContinue)) {
    $markerPath=Join-Path $d.FullName $Marker
    if (Test-Path -LiteralPath $markerPath -PathType Leaf) {
      try { if (([IO.File]::ReadAllText($markerPath)).Trim() -ceq $MarkerValue) { return $d.FullName } } catch {}
    }
  }
  return $null
}
if ($existing=FindImported) { Report ('success-existing '+$existing); exit 0 }
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class NativeSK4 {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint data, UIntPtr extraInfo);
}
'@
$ae = [System.Windows.Automation.AutomationElement]
$scope = [System.Windows.Automation.TreeScope]::Descendants
$trueCond = [System.Windows.Automation.Condition]::TrueCondition
function Elements($parent, [int]$max=700) {
  try {
    $collection=$parent.FindAll($scope,$trueCond)
    $count=[Math]::Min($collection.Count,$max)
    $list=New-Object 'System.Collections.Generic.List[System.Windows.Automation.AutomationElement]'
    for ($i=0;$i -lt $count;$i++) { $list.Add($collection.Item($i)) }
    return ,$list
  } catch { return ,(New-Object 'System.Collections.Generic.List[System.Windows.Automation.AutomationElement]') }
}
function FindByName($parent,[string[]]$names) {
  $all=Elements $parent
  foreach ($want in $names) {
    foreach ($el in $all) {
      try {
        $name=$el.Current.Name
        if ($name -and [string]::Equals($name.Trim(),$want,[StringComparison]::OrdinalIgnoreCase)) { return $el }
      } catch {}
    }
  }
  foreach ($want in $names) {
    foreach ($el in $all) {
      try {
        $name=$el.Current.Name
        if ($name -and $name.ToLowerInvariant().Contains($want.ToLowerInvariant())) { return $el }
      } catch {}
    }
  }
  return $null
}
function Invoke($el) {
  if ($null -eq $el) { return $false }
  $current=$el
  for ($i=0;$i -lt 3 -and $null -ne $current;$i++) {
    try {
      $p=$null
      if ($current.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern,[ref]$p)) {
        $p.Invoke(); return $true
      }
    } catch {}
    try {
      $r=$current.Current.BoundingRectangle
      if (-not $r.IsEmpty -and $r.Width -gt 3 -and $r.Height -gt 3) {
        $x=[int]($r.Left+$r.Width/2);$y=[int]($r.Top+$r.Height/2)
        if ($x -ge 0 -and $y -ge 0) {
          [NativeSK4]::SetCursorPos($x,$y) | Out-Null
          [NativeSK4]::mouse_event(0x0002,0,0,0,[UIntPtr]::Zero)
          [NativeSK4]::mouse_event(0x0004,0,0,0,[UIntPtr]::Zero)
          return $true
        }
      }
    } catch {}
    try { $current=[System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($current) } catch { break }
  }
  return $false
}
function FindLauncherWindow {
  try {
    $windows=[System.Windows.Automation.AutomationElement]::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children,$trueCond)
    for($i=0;$i -lt $windows.Count;$i++) {
      $item=$windows.Item($i)
      $name=$item.Current.Name
      if ($name -and $name -match '(?i)sk\s*launcher') { return $item }
    }
  } catch {}
  return $null
}
function OpenFileDialog {
  try {
    $windows=[System.Windows.Automation.AutomationElement]::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children,$trueCond)
    for($i=0;$i -lt $windows.Count;$i++) {
      $w=$windows.Item($i)
      # No tocar dialogos pertenecientes a otras aplicaciones abiertas.
      try {
        $dialogProcess=[Diagnostics.Process]::GetProcessById($w.Current.ProcessId)
        if ($dialogProcess.ProcessName -notmatch '(?i)^sklauncher') { continue }
      } catch { continue }
      if ($w.Current.ClassName -ne '#32770' -and $w.Current.Name -notmatch '(?i)^(open|abrir|select.*file|seleccionar archivo)') { continue }
      $edit=$null
      $all=Elements $w 250
      foreach($candidate in $all) {
        try {
          if ($candidate.Current.ControlType -eq [System.Windows.Automation.ControlType]::Edit -and
              ($candidate.Current.AutomationId -eq '1148' -or $candidate.Current.Name -match '(?i)file name|nombre de archivo')) { $edit=$candidate;break }
        } catch {}
      }
      if ($null -eq $edit) { continue }
      $value=$null
      if ($edit.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern,[ref]$value)) {
        $value.SetValue($Modpack)
        $openBtn=FindByName $w @('Open','Abrir')
        if ($null -ne $openBtn -and (Invoke $openBtn)) { Report 'dialog.file-submitted';return $true }
        [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
        Report 'dialog.file-enter'; return $true
      }
    }
  } catch { Report ('dialog.error '+$_.Exception.Message) }
  return $false
}
Report 'start'
try {
  $p=Start-Process -FilePath $Launcher -ArgumentList '--force-renderer-accessibility' -PassThru -ErrorAction Stop
  Report ('launcher.start '+$p.Id)
} catch {
  Report ('launcher.start-failed '+$_.Exception.Message)
  exit 4
}
$end=(Get-Date).AddSeconds($TimeoutSeconds)
$stage=0
$lastAct=Get-Date '2000-01-01'
while((Get-Date) -lt $end) {
  if ($existing=FindImported) { Report ('success '+$existing); exit 0 }
  $win=FindLauncherWindow
  if ($null -eq $win) { Start-Sleep -Milliseconds 600; continue }
  try { [NativeSK4]::SetForegroundWindow([IntPtr]$win.Current.NativeWindowHandle) | Out-Null } catch {}
  if (OpenFileDialog) { $stage=3; $lastAct=Get-Date; Start-Sleep -Seconds 2; continue }
  if (((Get-Date)-$lastAct).TotalSeconds -lt 1.5) { Start-Sleep -Milliseconds 350; continue }
  switch($stage) {
    0 {
      $btn=FindByName $win @('Library','Biblioteca','My instances','Mis instancias')
      if ($null -ne $btn -and (Invoke $btn)) { Report 'clicked.library';$stage=1;$lastAct=Get-Date }
    }
    1 {
      $btn=FindByName $win @('Import','Importar','Import instance','Import modpack')
      if ($null -ne $btn -and (Invoke $btn)) { Report 'clicked.import';$stage=2;$lastAct=Get-Date }
    }
    2 {
      $btn=FindByName $win @('From file','From a file','From local file','Desde archivo','Desde un archivo','Archivo')
      if ($null -ne $btn -and (Invoke $btn)) { Report 'clicked.from-file';$stage=3;$lastAct=Get-Date }
      # If Import opens the system picker directly, OpenFileDialog handles it above.
    }
    3 {
      # La pantalla de confirmacion del .mrpack debe quedar activa.
      # Se aceptan acciones de importacion explicitas, no 'Play' ni 'Delete'.
      $btn=FindByName $win @('Import modpack','Import instance','Import','Importar modpack','Importar instancia','Importar','Install modpack','Instalar modpack','Continue','Continuar')
      if ($null -ne $btn -and (Invoke $btn)) { Report 'clicked.confirm-import';$stage=4;$lastAct=Get-Date }
    }
    4 {
      # No repetir confirmaciones que podrian generar otra instancia. Verificar marcador.
    }
  }
  Start-Sleep -Milliseconds 500
}
Report ('pending-ui-stage '+$stage)
exit 2
