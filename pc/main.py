import os
import sys
import time
import threading
import uvicorn
import webview
from pathlib import Path

# Add base directory to sys.path
base_dir = Path(__file__).resolve().parent
sys.path.insert(0, str(base_dir))

from server import app, SERVER_PORT, APP_VERSION

PORT = SERVER_PORT

class DesktopApi:
    """Desktop native API exposed to webview JavaScript."""
    def select_files(self):
        """Open native Windows file dialog."""
        window = webview.active_window()
        if not window:
            return []
        try:
            res = window.create_file_dialog(webview.OPEN_DIALOG, allow_multiple=True)
            return list(res) if res else []
        except Exception as e:
            print(f"[!] 打开文件对话框失败: {e}")
            return []

    def select_folder(self):
        """Open native Windows folder dialog."""
        window = webview.active_window()
        if not window:
            return ""
        try:
            res = window.create_file_dialog(webview.FOLDER_DIALOG)
            return res[0] if (res and len(res) > 0) else ""
        except Exception as e:
            print(f"[!] 打开文件夹对话框失败: {e}")
            return ""

def run_server():
    """Start uvicorn server in background thread."""
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w")
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=PORT,
        log_level="warning",
        timeout_keep_alive=3600,
        ws_ping_interval=20.0,
        ws_ping_timeout=90.0
    )

def _wait_for_server(timeout=15.0):
    """Wait until the local HTTP service actually answers."""
    import urllib.request
    deadline = time.time() + timeout
    probe = f"http://127.0.0.1:{PORT}/api/ping"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(probe, timeout=1.0) as resp:
                if resp.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(0.25)
    return False

def main():
    print("=" * 60)
    print("       跨屏快传 CrossTransfer - Windows PC 桌面端")
    print(f"       版本 v{APP_VERSION}   服务端口: {PORT}")
    print("=" * 60)

    # Start FastAPI server in daemon thread
    server_thread = threading.Thread(target=run_server, daemon=True)
    server_thread.start()

    if not _wait_for_server(15.0):
        print("[!] 本地服务启动超时，仍尝试打开窗口...")

    url = f"http://127.0.0.1:{PORT}/"
    api = DesktopApi()

    print(f"\n[+] 本地管理服务已就绪: {url}")
    print("[+] 正在启动原生桌面端窗口...\n")

    # Launch native desktop application window
    try:
        window = webview.create_window(
            title="跨屏快传 CrossTransfer - Windows 桌面端",
            url=url,
            width=1160,
            height=780,
            min_size=(900, 600),
            text_select=True,
            js_api=api
        )
        webview.start(debug=False)
    except Exception as e:
        print(f"[!] 原生窗口启动异常: {e}，正在尝试兼容模式...")
        # Fallback to Chrome/Edge app mode or system browser
        import subprocess, webbrowser
        candidates = [
            r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
            r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
            r"C:\Program Files\Google\Chrome\Application\chrome.exe",
            r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
        ]
        opened = False
        for browser in candidates:
            if os.path.exists(browser):
                try:
                    subprocess.Popen([browser, f"--app={url}", "--window-size=1160,780"])
                    opened = True
                    break
                except Exception:
                    pass
        if not opened:
            webbrowser.open(url)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass

    print("[*] 桌面端已关闭，服务退出。")

if __name__ == "__main__":
    import multiprocessing
    multiprocessing.freeze_support()
    main()
