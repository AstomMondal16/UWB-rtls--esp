# Header action order checkpoint — 2026-10-02

Snapshot saved before swapping the CSV and Start/Pause action positions.

## Restore

From the repository root in PowerShell:

```powershell
Copy-Item -LiteralPath 'checkpoints\20261002-actions-order-before\MainActivity.kt' -Destination 'android\app\src\main\java\com\example\myapplication\MainActivity.kt' -Force
```
