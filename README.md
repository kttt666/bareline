# Bareline

> 一个几乎没有界面的安卓浏览器。打开链接、扫二维码，剩下的地方全留给网页。

<p align="center">
  <img src="docs/screenshots/01-home.jpg" width="22%" alt="首页">
  <img src="docs/screenshots/02-pin.jpg" width="22%" alt="密码页">
  <img src="docs/screenshots/03-vault.jpg" width="22%" alt="密码库">
  <img src="docs/screenshots/04-dsh.jpg" width="22%" alt="网页">
</p>

---

## 为什么做这个

大多数手机浏览器打开就是一堆东西：地址栏、标签栏、书签栏、资讯流、推荐位……
真正想看的内容被挤到下面一小块。

Bareline 反过来：**整屏都是网页**。所有控件收在一个半透明小圆钮里，点了才出来。

它最开始是为了搭配 [dsh-pocket](https://github.com/shaobeichen/dsh-pocket) 用的 ——
手机扫码连上电脑上的 DSH，然后当成一个纯粹的 WebView 用。
但本身也能当日常的「只读链接」浏览器，和那个插件没有任何代码依赖。

---

## 特性

| | |
| --- | --- |
| 🖥 **零占位界面** | 网页铺满全屏，没有常驻地址栏、标签栏、工具栏 |
| ▸ **左边缘三角** | 常驻的只有一个 15dp 小三角，点它才放出 ☰，再点才进菜单 |
| ⚪ **不压网页按钮** | 三角贴在屏幕左边缘正中，不碰左下角，网页自己的按钮照常用 |
| 📷 **扫码直连** | 内置二维码扫描，扫到链接直接打开 |
| 🖼 **相册导入** | 扫不了屏幕上的码？截图后从相册选图识别 |
| 🔑 **密码库** | 存好 PIN，到密码页点一下蓝色钥匙，自动填入并提交 |
| 📎 **网页选文件** | 网页里的「上传附件」能正常唤起系统文件管理器 |
| 📱 **沉浸式全屏** | 状态栏、导航栏自动收起，边缘滑出后自动消失 |
| 🔗 **接收分享** | 从别的 App 分享链接过来可直接打开 |
| 🪶 **轻** | 约 300 KB，无第三方 SDK、无统计、无联网上报 |

**刻意不做的事**：搜索框、标签页、书签、下载管理、广告拦截、账号体系。
搜索请用专门的浏览器，这个不掺和。

---

## 界面

### 首页

```
        B A R E L I N E

   ┌─────────────────────┬────┐
   │  输入网址            │ ⛶  │   ← 扫码
   └─────────────────────┴────┘

     扫电脑上的二维码，直连 DSH

▸                                ← 左边缘的小三角，只有它常驻
```

### 点一下那个三角

三角在屏幕左侧正中，点它 → 旁边滑出 **☰ 小圆钮**；再点 ☰ → 面板展开：

- **地址栏** —— 当前网址，可直接改写跳转
- **后退 / 刷新 / 扫码**
- **最近打开** —— 最多 40 条，可单条删除或一键清空
- **🔑 密码库** —— 进入管理界面

```
▸ ☰              ← 第一下：放出按钮
▸ ☰ ┌──────────┐ ← 第二下：面板展开
    │ 最近打开   │
    │ 🔑 密码库  │
    └──────────┘
```

收起方式：点面板外任意处、再点一次 ☰、按返回键，或者连按两次三角。**面板关掉时 ☰ 会自动缩回去**，屏幕上又只剩那个三角，不占任何常驻位置。

> 这样设计是因为：DSH 自己的加号按钮就在左下角，常驻的 ☰ 会压住它。现在三角贴在屏幕左边缘中间，和网页内容互不干扰。

---

## 下载

**[⬇ 下载 bareline-1.1.0.apk](https://github.com/kttt666/bareline/releases/download/v1.1.0/bareline-1.1.0.apk)** · 315 KB

或从 [Releases](../../releases) 页挑版本。

- 最低支持 **Android 8.0**（API 26）
- 需要**相机权限**才能扫码，其余功能不需要任何权限
- 安装时若提示「未知来源」，在系统设置里允许一次即可

> ⚠️ 用自签名证书打包，没有上架任何应用商店。介意的话请自行从源码构建。

---

## 配合 dsh-pocket 使用

> Bareline 是独立项目，和 [dsh-pocket](https://github.com/shaobeichen/dsh-pocket) 没有代码依赖关系，
> 只是恰好能配合它用。**不装 dsh-pocket 也能当普通浏览器用。**

1. 电脑上装好 [dsh-pocket](https://github.com/shaobeichen/dsh-pocket)，在设置页的「手机访问」里会看到一个**局域网二维码**
2. 手机连**同一个 WiFi**
3. 打开 Bareline → 点输入框右边的 ⛶ → 扫那个二维码
4. 直接进入电脑上的 DSH 界面，不用手输密码

二维码里带的是 `http://<局域网IP>:<端口>/?token=<PIN>`。Bareline 打开后，
dsh-pocket 会认这个 token 并种一个 HttpOnly cookie，后续请求就带着它走。

**登录态跟着电脑上的 `dsh web` 进程走**：它一直开着，手机就不用再扫；
`dsh web` 重启或更新后 cookie 失效，需要重新扫一次码。

人在外面要用的话，在 dsh-pocket 里开「公网访问」，扫公网那个二维码即可。

### 每次都要输 PIN 太烦？

如果不想每次都扫码（比如 dsh web 重启过、或者你把局域网密码关掉了），用**密码库**：

1. 点 ☰ → 「🔑 密码库」→ 右上角「+ 新建」
2. 填名称（例如 `DSH-LAN`）和密码，保存
3. 之后打开任何**带密码框的页面**，左下角会自动多出一个**蓝色钥匙按钮**
4. 点钥匙 → 点那条记录 → 密码自动填入并提交

钥匙按钮只在「页面上确实存在可见密码框」**且**「密码库里至少有一条」时才出现，
不会在普通网页上碍眼。

---

## 从源码构建

不需要 Gradle，也不需要 Android Studio。只要有 **Android SDK** 和 **JDK**，
一条命令就能出包：

```bash
git clone <repo>
cd bareline
bash build.sh
# → dist/bareline-1.1.0.apk
```

### 依赖

| 项 | 要求 |
| --- | --- |
| Android SDK | 含 `build-tools` 与任一 `platforms/android-*` |
| JDK | 11 或以上（Android Studio 自带的 `jbr` 也行） |
| bash | Windows 上用 Git Bash / MSYS2 |
| ZXing core | 已随仓库附带 `libs/zxing-core.jar`；丢了跑 `bash fetch-deps.sh` |

SDK 和 JDK 会自动探测常见安装位置，探测不到再手动指定：

```bash
ANDROID_SDK_ROOT=/path/to/sdk JAVA_HOME=/path/to/jdk bash build.sh
```

### 可调的环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BARELINE_VERSION` | `1.0.0` | 版本名 |
| `BARELINE_VERSION_CODE` | `1` | 版本号（整数，升级必须递增） |
| `BARELINE_BUILD_DIR` | `C:\bareline-build` / `/tmp/bareline-build` | 中间产物目录，**不能含空格** |
| `BARELINE_KS_PASS` | `bareline-local` | 签名密钥口令 |
| `BARELINE_BUILD_TOOLS` | 最新 | 指定 build-tools 版本 |
| `BARELINE_PLATFORM` | 最新 | 指定 platform 版本 |

### 构建流程

```
aapt2 compile → aapt2 link → javac → d8 → jar uf → zipalign → apksigner
```

`build.sh` 首次运行会自动生成 `dist/bareline.keystore`（RSA 2048，有效期 10000 天）。

> **这个文件要自己保管好。** 换签名密钥后旧版本无法覆盖升级，必须先卸载再装。
> 它也已经在 `.gitignore` 里，不会被提交。

---

## 实现笔记

这部分是踩过的坑，写给想改代码的人。

### 极简界面：从滑动 sheet 到边缘三角

演进过三代：

1. **底部可拖拽 sheet** —— 一直占着屏幕下方一块，网页被压住，滑出来还容易误触
2. **左下角圆钮** —— 网页满屏了，但 DSH 自己的加号就在左下角，被压住 2/3
3. **左边缘三角 + 按需放出 ☰**（当前）—— 常驻的只有一个 15dp 半透明三角，
   贴在屏幕左侧正中；点它才滑出 ☰，再点 ☰ 才展开面板

```java
private void setRail(boolean open) {   // 三角 ⇄ ☰
    railOpen = open;
    if (open) { fab.setVisibility(VISIBLE); fab.animate().alpha(1f).translationX(0f)... }
    else      { fab.animate().alpha(0f).translationX(-dp(8))... .withEndAction(GONE) }
}

private void closePanel() {
    ...
    .withEndAction(() -> {
        panel.setVisibility(GONE);
        scrim.setVisibility(GONE);
        setRail(false);      // 面板关了就把 ☰ 一起收回去
    })
}
```

- 面板用 `alpha` + `translationX` 做 150ms 入场动画，不用 `ValueAnimator` 改高度
- `onBackPressed` 的判断顺序：密码库浮层 → 面板 → 三角 → 网页后退
- **`R.drawable.xxx` 只认 `ImageButton` 类型的字段**，声明成 `View` 调
  `setImageResource()` 编译不过

### 沉浸式全屏：两个方法，顺序不能错

```java
setupWindow();                 // 窗口属性 —— 必须在 setContentView 之前
setContentView(R.layout.activity_main);
hideBars();                    // 收栏 —— 必须在之后
```

- `setupWindow()` 设 `setDecorFitsSystemWindows(false)` 和
  `layoutInDisplayCutoutMode = ALWAYS`，让内容真正铺到系统栏和刘海底下
- `hideBars()` 用 `WindowInsetsController` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
  （滑出即隐，不是普通 `IMMERSIVE` 那种滑出来就赖着不走）
- **在 `setContentView` 之前调 `getInsetsController()` 会直接 NPE** —— 那时 DecorView 还没建
- 必须在 `onWindowFocusChanged` 里再收一遍：从相册、相机、权限弹窗回来时，
  系统会把状态栏恢复出来

**顶部一条和状态栏等高的黑边** 就是少了前两项造成的：栏藏了，但窗口还在给它留位置；
有刘海的机器还会额外被系统往下压一次。模拟器没刘海，所以这个问题只有真机才暴露。

### 扫码：系统 Camera + ZXing

不依赖任何相机库：

- 系统 `Camera` API 取 NV21 帧，预览尺寸限制在 720p 以内
- ZXing core 解 `QR_CODE`，开 `TRY_HARDER`
- `AtomicBoolean` 防重入，命中即回调并关闭预览
- 预览期间连续对焦，暗光下有手电筒

### 相册导入的两个坑

1. **系统图片选择器返回的 `content://` URI 只能稳定打开一次。**
   先读尺寸、再解码、最后读 EXIF 各开一次流，第二次就 `ENOENT`。
   所以整个文件先读成 `byte[]`（上限 64 MB），后面全走内存。
2. **不能和预览共用一个 `MultiFormatReader`。**
   `decodeWithState` 不是线程安全的，两边并发会互相破坏内部状态。
   相册解码时另建 reader，并用 `volatile boolean importing` 暂停预览解码。

另外按 1280 / 2048 / 4096 三档尺寸各试一遍（截图里二维码常只占一小块，
缩到固定尺寸容易糊掉），失败再试反色（深色主题截图）。

### 密码库：React 页面填值的坑

**直接 `el.value = x` 是没用的。** React / Vue 的受控组件感知不到，
下一次渲染会把值改回去。必须走原生 setter 并补派事件：

```js
Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')
  .set.call(el, secret);
el.dispatchEvent(new Event('input',  { bubbles: true }));
el.dispatchEvent(new Event('change', { bubbles: true }));
```

再补 `keyup`，然后点提交。

**防误触**：只在「当前表单里恰好 1 个密码框」时才自动点 submit。
注册页那种「密码 + 确认密码」有两个框，就只填值不提交。

**存储**：密码字段用 Android Keystore 的 AES-256-GCM 加密后再 Base64 落盘，
密钥在 Keystore 里（`bareline_vault_key`），root 也导不出来。
名称和账号明文存 —— 列表要展示。

**层级**：密码库浮层必须 `elevation="20dp"`，否则旁边那个 ☰ 会浮在它上面，
把列表第一条盖住。

### 网页选文件：空壳 WebChromeClient 是无声的

网页里点「上传附件」没任何反应，也不报错 —— 因为默认的 `WebChromeClient`
对 `onShowFileChooser()` 返回 `false`，什么都不做。**跟存储权限无关**：
网页文件选择器不需要 App 自己申请存储权限。

```java
web.setWebChromeClient(new WebChromeClient() {
    @Override
    public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                     FileChooserParams params) {
        if (filePathCallback != null) filePathCallback.onReceiveValue(null);  // 收尾上一次
        filePathCallback = cb;
        ...
    }
});
```

几个细节：

- **先收尾上一次的回调**，否则第二次点击永远不响应
- `params.getAcceptTypes()` 可能返回 `.txt` 这种**扩展名**，直接塞
  `setType()` 会变无效 MIME，要用 `MimeTypeMap` 转；多个类型放 `EXTRA_MIME_TYPES`
- **别套 `Intent.createChooser`**，部分机型会吞掉结果，直接
  `startActivityForResult`
- 无论用户选没选，都必须 `onReceiveValue` 调**恰好一次**，否则页面一直卡着
- 还要开 `setAllowFileAccess(true)` + `setAllowContentAccess(true)`，
  否则 `content://` 交给页面时会被拦

### 代码结构

```
src/com/littlesauce/bareline/
├── MainActivity.java   主界面：WebView、面板、沉浸式、密码库接线
├── ScanActivity.java   扫码页：相机预览、ZXing 解码、相册导入
├── VaultSheet.java     密码库浮层 + 注入用 JS
├── Vault.java          密码库存储（加密落盘）
├── Crypto.java         Android Keystore AES-GCM 封装
├── Store.java          最近打开记录
└── UrlFix.java         网址补全（局域网走 http，其余 https）
```

---

## 已知取舍

- **只认二维码**，不扫条形码 —— 用途就是扫连接码
- **没有搜索框** —— 故意的
- 最近记录纯本地 `SharedPreferences`，不跨设备同步
- WebView 开了 `usesCleartextTraffic`，因为局域网 DSH 走 http
- 目标 SDK 34，未适配 Android 15 的 edge-to-edge 强制行为（当前沉浸式方案已兼容）

## 隐私

- 不联网上报任何数据，没有统计 SDK、没有崩溃收集
- 不读取通讯录、短信、位置、存储（相册只通过系统选择器拿单张图）
- 最近记录和密码库都存在应用私有目录，卸载即清除
- 密码字段加密存储，密钥由 Android Keystore 保管

## 许可证

[MIT](LICENSE)

第三方依赖：[ZXing](https://github.com/zxing/zxing) core 3.5.3，Apache-2.0。
