import io

SMALI = r"C:/Users/ADMINI~1/AppData/Local/Temp/dec/smali_classes3/com/inkstone/app/MainActivity.smali"

lines = io.open(SMALI, encoding="utf-8").read().split("\n")

# 只在 afterBackProbe 方法体内操作
start = next(i for i, l in enumerate(lines) if l.startswith(".method private final afterBackProbe"))
end = next(i for i in range(start, len(lines)) if lines[i].strip() == ".end method")

block = lines[start:end]
cond1 = next(i for i, l in enumerate(block) if l.strip() == ":cond_1")
cond2 = next(i for i in range(cond1, len(block)) if block[i].strip() == ":cond_2")

removed = 0
for i in range(cond1, cond2):
    if "exitArmedAt:J" in block[i]:
        print("删除:", block[i].strip())
        del block[i]
        removed += 1
        break

if removed == 0:
    print("missing 分支里没有清零指令（可能已处理）")
else:
    lines[start:end] = block
    io.open(SMALI, "w", encoding="utf-8", newline="\n").write("\n".join(lines))

# 复查
block2 = lines[start:start + len(block)]
c1 = next(i for i, l in enumerate(block2) if l.strip() == ":cond_1")
c2 = next(i for i in range(c1, len(block2)) if block2[i].strip() == ":cond_2")
print("missing 分支现在内容:")
for l in block2[c1:c2]:
    if l.strip():
        print("   ", l.strip())
