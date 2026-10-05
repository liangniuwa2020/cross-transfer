# 跨屏快传 (CrossTransfer) - Android & Windows PC 互穿互传软件

基于局域网高速穿透协议开发的 Android 与 Windows 电脑无缝文件互传工具。PC 端生成二维码，安卓端相机扫码即连，支持双向无限制传输普通文件、压缩包（ZIP/RAR/7z）、超大高清视频、原图相册等。

---

## 📦 项目位置与生成文件

项目已完整迁移至用户指定工作目录：
📁 **`E:\Antigravity ex_project\cross-transfer`**

| 平台 | 文件类型 | 路径 |
| :--- | :--- | :--- |
| **Android 手机端** | **APK 安装包** (约 6.7 MB) | `E:\Antigravity ex_project\cross-transfer\bin\CrossTransfer.apk` |
| **Windows PC 端** | **独立免安装程序 (EXE)** | `E:\Antigravity ex_project\cross-transfer\bin\CrossTransfer_PC\CrossTransfer_PC.exe` |
| **一键启动脚本** | **Windows 批处理脚本** | `E:\Antigravity ex_project\cross-transfer\启动电脑端.bat` |

---

## 🚀 使用步骤

### 1. 电脑端启动
1. 双击运行 `E:\Antigravity ex_project\cross-transfer\启动电脑端.bat`（或直接运行 `bin\CrossTransfer_PC\CrossTransfer_PC.exe`）。
2. PC 端将直接弹出**独立的 Windows 原生桌面端软件窗口**（无需打开浏览器，无黑框控制台），自动检测本地局域网 IP 并实时生成互联二维码。

### 2. 手机端连接
1. 将 `CrossTransfer.apk` 发送到安卓手机安装并打开。
2. 点击右上角 **【📷 扫码互联】**，授予相机权限后对准电脑屏幕上的二维码。
3. 扫描成功后，手机与电脑界面瞬间变为绿色 **【🟢 已连接】** 状态，PC 端自动展开【手机存储管理】面板。

### 3. 双向高速互传与文件夹拖拽
* **电脑 ➔ 手机**：
  * **任意拖拽**：直接把电脑上的**任意单个文件、多个文件，甚至整个文件夹**拖拽到 PC 窗口的任意区域（包含主窗口与手机文件夹管理弹窗）。
  * **文件夹智能处理**：拖拽文件夹时支持选择【📦 打包为 ZIP 压缩包发送】或【📂 保持文件夹层级直接传输】。
  * **本地按钮**：也可点击【选择电脑文件】或【选择电脑文件夹】直接选取。
  * 手机端实时显示传输进度条与速率（MB/s），接收完毕后保存在手机中。
* **手机 ➔ 电脑**：
  * 在手机端点击 **【📦 文件 / 压缩包】** 或 **【🖼️ 照片 / 视频】**，挑选要发送的内容。
  * 电脑端自动接收并存放在 `下载目录\CrossTransfer` 中，可点击【打开文件】或【打开下载文件夹】。
  * 亦可在 PC 端的【手机存储管理】中浏览手机内部任意文件夹并一键点击【下载到电脑】。

---

## 🛠️ 项目源码架构

```text
E:\Antigravity ex_project\cross-transfer\
├── bin/
│   ├── CrossTransfer.apk              # 编译完成的安卓端 APK
│   └── CrossTransfer_PC/              # 打包好的 Windows 端软件目录
│       └── CrossTransfer_PC.exe       # PC 端运行主程序
├── android/                           # 安卓客户端原生工程 (Kotlin + CameraX/ZXing + OkHttp)
│   ├── app/
│   │   ├── src/main/java/com/crosstransfer/app/
│   │   │   ├── MainActivity.kt        # 扫码界面、状态响应与交互
│   │   │   ├── NetworkManager.kt      # 双向 WebSocket 通信与分块流传输
│   │   │   └── FileAdapter.kt         # 传输历史与文件调起
│   └── build.gradle
├── pc/                                # PC 端工程 (FastAPI + WebSocket + PyWebView + PyInstaller)
│   ├── main.py                        # 启动入口与原生窗体加载
│   ├── server.py                      # 局域网网卡检测、二维码生成与文件收发服务
│   └── static/                        # 现代化交互界面 (HTML/CSS/JS)
└── 启动电脑端.bat                      # 电脑端一键启动快捷脚本
```
