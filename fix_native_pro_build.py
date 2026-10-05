from pathlib import Path

root = Path("android-collector/app/src/main/java/com/osmaicoach/collector")
main = root / "MainActivity.kt"

s = main.read_text(encoding="utf-8")
old = "fun CoachTheme(content:@Composable()->Unit){"
new = "fun CoachTheme(content: @Composable () -> Unit){"
if old not in s:
    raise SystemExit("Assinatura CoachTheme esperada não encontrada.")
s = s.replace(old, new, 1)
main.write_text(s, encoding="utf-8")

# Native Pro no longer uses the WebView JavaScript bridge.
bridge = root / "CoachBridge.kt"
if bridge.exists():
    bridge.unlink()

print("Native Pro build fix applied.")
