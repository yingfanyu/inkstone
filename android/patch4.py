import io

SMALI = r"C:/Users/ADMINI~1/AppData/Local/Temp/dec/smali_classes3/com/inkstone/app/MainActivity.smali"

lines = io.open(SMALI, encoding="utf-8").read().split("\n")

start = next(i for i, l in enumerate(lines) if l.startswith(".method private final afterBackProbe"))
end = next(i for i in range(start, len(lines)) if lines[i].strip() == ".end method")

cond2 = next(i for i in range(start, end) if lines[i].strip() == ":cond_2")

target = -1
for i in range(cond2, end):
    if "MainActivity;->confirmExit()V" in lines[i]:
        target = i
        break

if target < 0:
    raise SystemExit("没找到 :cond_2 后面的 confirmExit 调用")

print("原指令:", lines[target].strip())
lines[target] = "    goto :cond_3"

io.open(SMALI, "w", encoding="utf-8", newline="\n").write("\n".join(lines))

# 复查
b = lines[start:end]
c2 = next(i for i, l in enumerate(b) if l.strip() == ":cond_2")
print("修改后 :cond_2 附近:")
for l in b[c2:c2 + 6]:
    if l.strip():
        print("   ", l.strip())
