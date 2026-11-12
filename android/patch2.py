import io
import os
import re
import shutil

DEC = r"C:/Users/ADMINI~1/AppData/Local/Temp/dec"
MANIFEST = DEC + "/AndroidManifest.xml"
SHORTCUT_SRC = r"C:/Users/Administrator/WorkBuddy/2026-11-12-11-06-29/apk-build/res/xml/shortcuts.xml"

manifest = io.open(MANIFEST, encoding="utf-8").read()

# 1) SettingsActivity 不再作为桌面入口：去掉 intent-filter，保留 exported（快捷方式要用）
settings_block = re.search(
    r'(<activity android:name="com\.inkstone\.app\.SettingsActivity"[^>]*>)\s*<intent-filter>.*?</intent-filter>\s*(</activity>)',
    manifest, re.S)
if settings_block:
    manifest = manifest[:settings_block.start()] + settings_block.group(1) + settings_block.group(2) + manifest[settings_block.end():]
    print("settings launcher removed")
else:
    print("settings launcher: already removed or not found")

# 2) MainActivity 挂静态快捷方式
if "android.app.shortcuts" not in manifest:
    manifest = manifest.replace(
        'android:name="com.inkstone.app.MainActivity"',
        'android:name="com.inkstone.app.MainActivity"', 1)
    anchor = '</activity>'
    idx = manifest.find(anchor)
    meta = ('\n            <meta-data android:name="android.app.shortcuts" '
            'android:resource="@xml/shortcuts"/>')
    manifest = manifest[:idx] + meta + manifest[idx:]
    print("shortcut meta-data added")
else:
    print("shortcut meta-data: already present")

io.open(MANIFEST, "w", encoding="utf-8", newline="\n").write(manifest)

# 3) 放入 shortcuts.xml 资源
os.makedirs(DEC + "/res/xml", exist_ok=True)
shutil.copyfile(SHORTCUT_SRC, DEC + "/res/xml/shortcuts.xml")
print("shortcuts.xml copied")

# 4) 自查
m = io.open(MANIFEST, encoding="utf-8").read()
print("launcher filters:", m.count('android.intent.category.LAUNCHER'))
print("shortcuts meta:", 'android.app.shortcuts' in m)
