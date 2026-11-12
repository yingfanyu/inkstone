# Inkstone 安卓客户端（自托管改造版）

在官方 APK 基础上做**补丁式改造**，不重写界面：保留原有的指纹锁、下载、错误重试、
JS 桥（`InkstoneHost`）等全部功能，只替换地址解析与返回键逻辑。

产物：`Inkstone-nas.apk`（`com.inkstone.app`，minSdk 26 / target 34）。

## 改造内容

| 能力 | 实现 |
| --- | --- |
| 启动前解析 DNS TXT 拿端口 | `DnsTxt`（手撸 UDP DNS 查询，多解析器回退，无第三方库） |
| 域名/端口状态机 | `Endpoint`（域名可长期覆盖；端口当天有效，跨天重新解析；失败回退上次端口 → 老域名） |
| 最早执行注入 | `InkstoneInit`（ContentProvider，把结果写进 `MainActivity.START_URL` / `TRUSTED_HOSTS`） |
| 设置页 | `SettingsActivity`（显示当前地址与登录态；覆盖需两次确认 + 指纹，无指纹时输入「确认覆盖」） |
| 设置入口 | 静态 App Shortcut（长按桌面图标「连接设置」）+ 三指轻点 + 长按错误面板 |
| 返回键 | `BackHandler`（点页面自身的「返回笔记列表」按钮；没有该按钮才提示「再按一次退出」） |
| Cookie 落盘 | `SettingsLauncher.flushCookies()`（避免进程被杀丢登录态） |

默认值在 `Endpoint.java`：

```java
DEFAULT_HOST = "note.1.929295.xyz";   // 证书是 *.929295.xyz 泛域名
TXT_NAME     = "note2.929295.xyz";    // TXT 内容就是端口号，如 "9262"
LEGACY_URL   = "https://note.929295.xyz/";  // 全部失败时的兜底
```

## 构建

需要：JDK 17、Android SDK build-tools 34、apktool、Python。
路径在 `build.sh` 顶部按需修改（默认指向 `.workbuddy/skills/android-app-builder/toolchain`）。

```bash
# 1) 反编译原始包（只一次）
apktool d -f -o /tmp/dec Inkstone.apk

# 2) 打补丁（顺序执行，各自幂等）
python patch.py     # 6 处 URL 常量 -> Endpoint.url()；两个字段改为可写；注册 provider/设置页
python patch2.py    # 去掉设置页的桌面入口，挂静态快捷方式
python patch3.py    # 修 afterBackProbe 的 missing 分支（1.3 用，保留以备回溯）
python patch4.py    # missing 分支改为复用 canGoBack 判断（1.4 用）
# 1.5 起 onBackPressed 直接改调 BackHandler，patch.py 之后需要再执行下面这句：
#   sed -i 's#invoke-direct {p0}, Lcom/inkstone/app/MainActivity;->handleBack()V#invoke-static {p0}, Lcom/inkstone/app/BackHandler;->handle(Landroid/app/Activity;)V#' /tmp/dec/smali_classes3/com/inkstone/app/MainActivity.smali

# 3) 编译打包
bash build.sh
```

`build.sh` 流程：javac → d8 → apktool b → 追加 `classes4.dex` → zipalign → apksigner。

补丁脚本里的 `/tmp/dec` 与 `C:/Users/ADMINI~1/.../Temp/dec` 是绝对路径，换机器要改。

## 签名

签名密钥不在仓库里（会泄露应用身份）。本地位置：`inkstone.jks`，
`storepass` / `keypass` 见构建脚本。**换了签名就必须先卸载旧版**，WebView 本地缓存和登录态会清空。

## 注意

- 应用只监听 WebView 的 http/https 地址，界面语言跟随网页；「返回笔记列表」按钮的
  `aria-label` 取自网页 i18n，改动网页文案会导致返回键失效（`BackHandler.CLICK_BACK_JS` 里有中英两份）。
- 端口来自 TXT 记录，跨天才会变；开机当天若解析失败会沿用上次成功的端口，仍失败才回退老域名。
