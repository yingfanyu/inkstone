import io
import re
import sys

SMALI = r"C:/Users/ADMINI~1/AppData/Local/Temp/dec/smali_classes3/com/inkstone/app/MainActivity.smali"
MANIFEST = r"C:/Users/ADMINI~1/AppData/Local/Temp/dec/AndroidManifest.xml"

URL = '"https://note.929295.xyz/"'
CALL = ("invoke-static {}, Lcom/inkstone/app/Endpoint;->url()Ljava/lang/String;\n"
        "%smove-result-object %s\n")

src = io.open(SMALI, encoding="utf-8").read()

# 1) 六处内联的 START_URL 常量 -> 运行时解析出来的地址
pattern = re.compile(r'^([ \t]*)const-string ([vp]\d+), ' + re.escape(URL) + r'$', re.M)
count = 0


def repl(m):
    global count
    count += 1
    return CALL % (m.group(1), m.group(2))


src = pattern.sub(repl, src)

# 2) 两个静态字段改成可写，供 Endpoint 在启动时注入
src = src.replace(
    '.field private static final START_URL:Ljava/lang/String; = %s' % URL,
    '.field public static START_URL:Ljava/lang/String; = %s' % URL)
src = src.replace(
    '.field private static final TRUSTED_HOSTS:Ljava/util/Set;',
    '.field public static TRUSTED_HOSTS:Ljava/util/Set;')

io.open(SMALI, "w", encoding="utf-8", newline="\n").write(src)
print("url sites patched:", count)
print("START_URL writable:", ".field public static START_URL" in src)
print("TRUSTED_HOSTS writable:", ".field public static TRUSTED_HOSTS" in src)

# 3) 清单：注册 ContentProvider（最早执行）与独立设置入口
manifest = io.open(MANIFEST, encoding="utf-8").read()

provider = (
    '        <provider android:name="com.inkstone.app.InkstoneInit" '
    'android:authorities="com.inkstone.app.init" android:exported="false" android:initOrder="100"/>\n'
)
settings_activity = (
    '        <activity android:name="com.inkstone.app.SettingsActivity" android:exported="true" '
    'android:label="Inkstone 设置" android:theme="@style/AppTheme" '
    'android:configChanges="density|fontScale|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.intent.action.MAIN"/>\n'
    '                <category android:name="android.intent.category.LAUNCHER"/>\n'
    '            </intent-filter>\n'
    '        </activity>\n'
)

if "InkstoneInit" not in manifest:
    manifest = manifest.replace("    </application>", provider + settings_activity + "    </application>")

io.open(MANIFEST, "w", encoding="utf-8", newline="\n").write(manifest)
print("manifest provider:", "InkstoneInit" in manifest)
print("manifest settings:", "SettingsActivity" in manifest)
