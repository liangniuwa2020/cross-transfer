# 跨屏快传 (CrossTransfer) - 版本迭代与修改文档 (CHANGELOG)

本文档严格记录【跨屏快传】项目的每一次版本迭代、功能修改、文件变动与发布产物。

---

## 📌 [v1.3.0] - 2026-10-05 11:00

### 1. 修改目的与用户需求
* 满足用户提出的核心需求：
  1. “**传输文件，pc端可以直接拖拽相关文件到手机端，现在文件夹是不行的，拖文件也不行，请修改**”
  2. “**桌面端 ，点击以后，要求生成一个桌面端，而不是在网页里面生成一个页面**”

### 2. 具体功能修复与优化
* **【PC 原生桌面端】**
  * **真正的独立原生桌面窗体**：重构 `pc/main.py`，集成 `pywebview` 引擎（基于 Windows 内置 Edge Chromium WebView2 运行时），点击后直接弹出独立的 Windows 桌面应用窗体，拥有专属标题栏、图标、窗口边框与缩放/最小化/最大化控制，彻底摆脱系统浏览器标签页与地址栏。
  * **一键无黑框独立启动**：修改 `CrossTransfer_PC.spec`，将打包入口切换为 `main.py` 并配置 `console=False`，重新编译生成 `bin/CrossTransfer_PC/CrossTransfer_PC.exe`（8.6 MB 免安装独立程序）。彻底移除 `启动电脑端.bat` 与 `一键启动(无黑框).vbs` 中的浏览器唤起命令（`start http://...`），双击脚本或主程序直接启动原生桌面应用。
  * **全窗口全局拖拽穿透与捕获**：在 `window` 全局挂载 `dragover` / `drop` 阻止浏览器默认跳转行为，解决之前在窗口空白区、标题区或手机存储管理模态弹窗内拖拽文件无效/导致浏览器打开文件离开页面的问题。
  * **全屏极速拖拽投递视觉层**：文件拖入窗口任意位置时，瞬间呈现半透明高科技感【全窗口投递遮罩】，动态提示目标手机目录。
  * **完整文件夹递归拖拽支持**：采用 HTML5 FileSystem API（`webkitGetAsEntry` / `createReader`）实现全层级深度递归扫描，彻底解决拖拽文件夹时被当成 0 字节空文件损坏的问题。
  * **文件夹双模式传输策略**：
    1. **📦 打包为压缩包发送 (.zip)（推荐）**：集成纯本地免联网 `jszip.min.js`，PC 端毫秒级在内存中打包为标准 ZIP 压缩包推送到手机，手机端接收后可直接解压预览。
    2. **📂 保持文件夹结构传输**：在手机端根据目录层级自动新建同名文件夹，并将全部子文件原样存入手机对应目录。
  * **新增【选择电脑文件夹】功能**：主界面与手机存储管理弹窗顶部新增【📂 选择电脑文件夹】按钮，调用 PyWebView 本地 Windows 文件夹选择器或标准目录选择器。
  * **批量多文件进度指示**：支持单文件、多文件批量拖入，实时展示 `[X/N]` 序号进度与传输速率。

* **【Android 手机端】**
  * **修复新建目录与多级子路径写入问题**：优化 `NetworkManager.kt` 的 `downloadFile` 逻辑，在目标目录不存在时主动先执行 `dir.mkdirs()`，并添加 `targetFile.parentFile?.mkdirs()` 保护，确保从电脑端推送的新建文件夹与多层子目录能 100% 成功落盘。
  * **版本配置升级**：`versionCode` 由 `4` 递增至 `5`，`versionName` 递增至 `"1.3.0"`。重新编译输出 `bin/CrossTransfer.apk` 和 `bin/CrossTransfer_v1.3.0.apk`。

### 3. 修改涉及的文件清单
* `pc/main.py`：使用 PyWebView 创建 Windows 原生独立桌面窗口，暴露 Native File/Folder Dialog API，无缝启动/关闭后台服务。
* `pc/CrossTransfer_PC.spec`：改为打包 main.py，配置 GUI 无黑框模式与 webview 依赖。
* `pc/static/index.html`：新增全局拖拽遮罩、文件夹传输选择模态框、【选择电脑文件夹】按钮，引入本地 jszip.min.js，升级 v1.3.0 标识。
* `pc/static/style.css`：新增全局拖拽全屏浮层、文件夹模式选择卡片、文件夹选择按钮样式。
* `pc/static/app.js`：实现全局拖拽监听、递归文件与文件夹解析、JSZip 客户端压缩、文件夹层级推送、PyWebView 原生选择器联动。
* `pc/static/jszip.min.js`：本地集成 97KB 离线 ZIP 压缩核心库。
* `pc/server.py`：改进 send_to_phone 文件名安全处理，增加 `/api/send_local_folder` 接口。
* `启动电脑端.bat`：移除 `start http://...` 浏览器唤起，直接运行原生可执行文件。
* `一键启动(无黑框).vbs`：移除浏览器唤起，直接无黑框运行原生桌面程序。
* `android/app/src/main/java/com/crosstransfer/app/NetworkManager.kt`：修复目录创建与子目录父路径生成。
* `android/app/build.gradle`：更新 `versionCode 5`, `versionName "1.3.0"`。

### 4. 本次编译输出产物
* **Android 安装包**：[`bin/CrossTransfer_v1.3.0.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_v1.3.0.apk)（别名：[`bin/CrossTransfer.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer.apk)）
* **Windows PC 原生桌面端程序**：[`bin/CrossTransfer_PC/CrossTransfer_PC.exe`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_PC/CrossTransfer_PC.exe)

---

## 📌 [v1.2.0] - 2026-10-04 23:05

### 1. 修改目的与用户需求
* 满足用户提出的核心需求：“**我要从pc端可以进入手机的文件夹**”。
* 将 PC 端的平面文件列表升级为完整的**全功能远程手机文件系统浏览器 (Remote File Explorer)**。
* 允许电脑端层层进入手机内部任意文件夹，支持返回上一级、根目录快捷跳转、一键下载手机文件到电脑，以及直接从电脑上传文件到指定的手机文件夹中。

### 2. 具体功能修复与优化
* **【Android 手机端】**
  * **所有文件管理权限支持（MANAGE_EXTERNAL_STORAGE）**：在 `AndroidManifest.xml` 中配置 `MANAGE_EXTERNAL_STORAGE` 权限与 `requestLegacyExternalStorage="true"`，并实现全盘文件管理权限引导逻辑 `checkAndRequestManageStorage()`。主界面提供直观的权限开启卡片，点击即可一键跳转至系统设置开启。
  * **文件夹递归遍历服务**：实现 `listDirectory(path)`，智能读取手机内部存储根目录（`/storage/emulated/0`）或指定子目录（`Download`、`DCIM`、`Pictures`、`Documents`、`Music`、`Movies` 等），递归汇总子项数量与修改时间，按“文件夹在前、文件在后”字母序排序，将结构化数据打包为 `directory_content` 报文回传 PC。
  * **定向保存到指定手机目录**：`downloadFile` 支持可选的 `target_dir` 参数。当电脑端在某个手机文件夹内点击“上传到当前文件夹”时，手机直接将收到的文件存入该目标文件夹。
  * **直接文件流高效传输**：新增 `sendFileDirect(file: File)`，使用原生 `FileInputStream` 高效分块推送到电脑端，绕过 ContentResolver 额外开销。
  * **版本配置升级**：`versionCode` 由 `3` 递增至 `4`，`versionName` 递增至 `"1.2.0"`。
* **【PC 电脑端】**
  * **全新远程手机文件管理器 (Explorer UI)**：弹窗扩充为宽屏专业文件浏览器，顶部包含【🏠 内部存储】、【⬅️ 返回上一级】、【🔄 刷新】、【📤 上传到当前文件夹】以及常用快捷目录芯片（Download、DCIM 相册、Pictures 图片、Documents 文档、Music 音乐、Movies 视频）。
  * **双向交互与层层深入**：在 PC 端点击任何文件夹整行或【进入 ➔】按钮，即可实时向手机发送目录请求并展开该文件夹内容；点击返回上一级可原路返回。
  * **一键下载与定向上传**：浏览任何手机文件夹中的文件时，点击【⬇️ 下载到电脑】即刻高速抓取到电脑本地；点击【📤 上传到当前文件夹】可选择电脑文件直接写入手机当前浏览的目录。
  * **权限提醒与远程唤起**：若手机未授予所有文件访问权限，顶部显示警告横幅并提供【在手机端弹出授权】按钮，一键遥控手机呼出系统授权设置。
  * **版本标识更新**：升级为 `v1.2.0`。

### 3. 修改涉及的文件清单
* `android/app/src/main/AndroidManifest.xml`：配置 MANAGE_EXTERNAL_STORAGE 权限与 requestLegacyExternalStorage。
* `android/app/src/main/java/com/crosstransfer/app/NetworkManager.kt`：实现 listDirectory 目录扫描、sendFileDirect 高效文件流与 target_dir 目标目录落盘。
* `android/app/src/main/java/com/crosstransfer/app/MainActivity.kt`：添加全盘文件管理权限引导、动态更新权限横幅。
* `android/app/src/main/res/layout/activity_main.xml`：新增全盘文件权限引导卡片。
* `android/app/build.gradle`：更新 `versionCode 4`, `versionName "1.2.0"`。
* `pc/server.py`：新增 `/api/list_phone_dir`、`/api/request_storage_permission` 接口、`/api/send_to_phone` 支持 target_dir、添加 freeze_support 与标准流守护。
* `pc/static/index.html`：重构手机存储弹窗为全功能远程文件夹浏览器，增加路径栏与常用快捷目录芯片。
* `pc/static/style.css`：新增文件夹浏览器宽屏布局、路径导航条、表格头与文件夹进入/下载样式。
* `pc/static/app.js`：实现目录请求、树形渲染、进入文件夹、返回上一级、定向上传与远程权限弹出。

### 4. 本次编译输出产物
* **Android 安装包**：[`bin/CrossTransfer_v1.2.0.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_v1.2.0.apk)（别名：[`bin/CrossTransfer.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer.apk)）
* **Windows PC 程序**：[`bin/CrossTransfer_PC/CrossTransfer_PC.exe`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_PC/CrossTransfer_PC.exe)

---

## 📌 [v1.1.1] - 2026-10-04 22:42

### 1. 修改目的与用户需求
* 解决用户反馈：“**读取手机存储一直循环，然后无法打开手机的存储和文件夹**”的问题。
* 彻底解决 Android 11+ 分区存储（Scoped Storage）异常导致通信中断。
* 彻底打通手机各品牌机型（小米/华为/OPPO/vivo等）的系统内部存储与文件夹浏览。
* 杜绝 PC 端界面长时间停留在“正在读取”菊花等待状态。

### 2. 具体功能修复与优化
* **【Android 手机端】**
  * **修复 Scoped Storage 异常与卡死问题**：在 `NetworkManager.kt` 中，高版本 Android (API 29+) 访问 `Environment.getExternalStoragePublicDirectory()` 会触发系统级 `SecurityException`，导致子线程异常中断未向 PC 返回消息。重构为安全查询已接收文件目录并使用 `ContentResolver` (MediaStore) 安全提取最近图片/视频/文档；增加 `finally` 兜底保护，确保 100% 返回 `storage_list` 响应包。
  * **修复手机存储与文件夹无法打开问题**：在 `MainActivity.kt` 中，将原 `ActivityResultContracts.GetMultipleContents()` 升级为系统标准存储框架 `ActivityResultContracts.OpenMultipleDocuments()`，直接调用 `ACTION_OPEN_DOCUMENT`，彻底解决小米/MIUI/HyperOS/OPPO等定制系统在媒体选择器中屏蔽文件夹树的问题，支持直接浏览手机“内部存储设备”、SD卡、下载、文档等全量文件夹层级并选取文件。
  * **响应电脑端远程唤起选择器**：新增 `open_file_picker` 协议，当电脑端点击唤起时，手机端直接弹出全能系统文件/目录浏览器。
  * **版本配置递增**：`versionCode` 由 `2` 递增至 `3`，`versionName` 递增至 `"1.1.1"`。
* **【PC 电脑端】**
  * **消除轮询死循环**：在 `pc/static/app.js` 中新增 `isPhoneModalOpened` 状态锁，防止 WebSocket 每一帧重复打开或刷新弹窗导致请求阻塞。
  * **超时安全兜底**：新增 3.5 秒等待超时保护机制，若手机端尚未返回文件列表，自动展示优雅的就绪状态面板（`renderPhoneStorageReadyFallback`），避免一直停留在“正在读取手机存储中...”的加载动画中。
  * **新增一键在手机端弹出选择器**：PC 模态弹窗顶部新增【📲 在手机端唤起存储选择器】按钮，点击即可远程在手机屏幕上展开系统存储管理器，手机选好后直接秒速回传到 PC。
  * **版本标识更新**：升级为 `v1.1.1`。

### 3. 修改涉及的文件清单
* `android/app/src/main/java/com/crosstransfer/app/NetworkManager.kt`：安全读写与 MediaStore 提取，finally 响应保证。
* `android/app/src/main/java/com/crosstransfer/app/MainActivity.kt`：使用 OpenMultipleDocuments 打开全盘文件树，监听远程呼叫打开选择器。
* `android/app/build.gradle`：更新 `versionCode 3`, `versionName "1.1.1"`。
* `pc/server.py`：新增 `/api/trigger_phone_picker` 远程呼起手机存储接口。
* `pc/static/index.html`：增加“在手机端唤起选择器”操作栏，更新版本标识至 `v1.1.1`。
* `pc/static/app.js`：添加加载防重复状态锁、3.5s 超时兜底与远程触发函数。
* `pc/static/style.css`：优化手机存储管理弹窗与就绪状态卡片样式。

### 4. 本次编译输出产物
* **Android 安装包**：[`bin/CrossTransfer_v1.1.1.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_v1.1.1.apk)（别名：[`bin/CrossTransfer.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer.apk)）
* **Windows PC 程序**：[`bin/CrossTransfer_PC/CrossTransfer_PC.exe`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_PC/CrossTransfer_PC.exe)

---

## 📌 [v1.1.0] - 2026-10-04 22:30

### 1. 修改目的与用户需求
* 满足用户提出的新交互需求：
  1. 安卓端扫码成功后，PC 端立即自动弹出手机界面面板；
  2. 点击可直接进入手机存储，浏览并选取手机中的文件；
  3. 双方均可随时手动点击【断开连接】按钮结束本次互联会话；
  4. 建立严格的版本编号管理机制与修改日志文档。

### 2. 具体功能改动
* **【PC 电脑端】**
  * **扫码自动弹窗**：手机扫码握手成功瞬间，PC 端自动弹出【手机存储管理】模态交互窗口。
  * **手机存储浏览器**：可在 PC 上直接请求并查看手机端的文件列表（含下载目录、相册、已接收文件等），每个文件均配有【下载到电脑】按钮，实现免数据线一键抓取手机文件。
  * **手动断开连接**：在弹窗工具栏及主界面设备卡片上新增醒目的红色【🔌 断开连接】按钮。点击后发送 `disconnect_peer` 指令并安全关闭通道，界面恢复待扫码状态。
  * **版本标识**：主界面标题栏新增 `v1.1.0` 徽标。
* **【Android 手机端】**
  * **进入手机存储**：连接成功后，主界面醒目展示【📂 进入手机存储】按钮，点击直接唤起系统级文件管理器（DocumentsProvider / Storage Access Framework），支持自由浏览内部存储、SD卡、微信等各目录文件。
  * **手动断开连接**：主界面新增红色【🔌 断开连接】按钮，点击发送 `phone_disconnect` 消息并切回未连接状态。
  * **远程取件支持**：响应电脑端的 `list_storage` 和 `send_file_to_pc` 请求，自动汇报手机文件并上传指定文件到电脑。
  * **版本配置升级**：`versionCode` 由 `1` 递增至 `2`，`versionName` 递增至 `"1.1.0"`。

### 3. 修改涉及的文件清单
* `pc/server.py`：新增 `/api/disconnect`、`/api/request_phone_storage`、`/api/request_phone_file` 接口及 WebSocket 双方断开广播。
* `pc/static/index.html`：新增版本徽标、设备操作按钮栏与手机存储管理弹窗 DOM。
* `pc/static/style.css`：新增弹窗、工具栏、手机存储列表与危险断开按钮的样式定义。
* `pc/static/app.js`：实现手机连接自动弹窗、手机文件加载渲染、下载请求与断开处理。
* `android/app/build.gradle`：更新 `versionCode 2`, `versionName "1.1.0"`。
* `android/app/src/main/res/layout/activity_main.xml`：新增进入存储与断开按钮控件。
* `android/app/src/main/java/com/crosstransfer/app/MainActivity.kt`：绑定进入存储与断开事件监听。
* `android/app/src/main/java/com/crosstransfer/app/NetworkManager.kt`：新增存储文件扫描、远程取件与主动断开逻辑。

### 4. 本次编译输出产物
* **Android 安装包**：[`bin/CrossTransfer_v1.1.0.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_v1.1.0.apk)（别名：[`bin/CrossTransfer.apk`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer.apk)）
* **Windows PC 程序**：[`bin/CrossTransfer_PC/CrossTransfer_PC.exe`](file:///E:/Antigravity%20ex_project/cross-transfer/bin/CrossTransfer_PC/CrossTransfer_PC.exe)
* **无黑框启动脚本**：[`启动电脑端(无黑框版).vbs`](file:///E:/Antigravity%20ex_project/cross-transfer/启动电脑端(无黑框版).vbs)

---

## 📌 [v1.0.0] - 2026-10-04 21:00

### 1. 初始版本功能
* 纯局域网免流量高速 P2P 穿透通信架构。
* PC 端自动扫描物理网卡，生成高清晰度动态配对二维码。
* 安卓端 CameraX 毫秒级二维码扫描与握手配对。
* 支持双向传输任意大小文件（ZIP/RAR压缩包、4K视频、高清图片、APK等）。
* 实时传输速率（MB/s）与百分比进度条监控。
* 接收记录展示，支持一键打开文件与定位文件夹。

### 2. 初始编译产物
* `CrossTransfer.apk`（Android 安装包，versionCode 1, versionName "1.0.0"）
* `CrossTransfer_PC.exe`（Windows 64 位免安装绿色程序）
