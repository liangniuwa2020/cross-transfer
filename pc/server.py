import os
import sys
import io
import time
import socket
import base64
import asyncio
import json
import uuid
import subprocess
import ctypes
from collections import OrderedDict
from pathlib import Path
from typing import List, Dict, Any, Optional

import psutil
import qrcode
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, Form, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

APP_VERSION = "1.5.0"
SERVER_PORT = 52020

app = FastAPI(title="CrossTransfer PC Server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Base download directory
DOWNLOADS_DIR = Path.home() / "Downloads" / "CrossTransfer"
DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)

# Connected websockets
connected_androids: Dict[str, WebSocket] = {}
connected_browsers: List[WebSocket] = []

# Staged files for phone downloading: file_id -> {"path": Path, "name": str, "size": int}
# 使用 OrderedDict 做 LRU 上限，避免长时间运行后内存与磁盘无限增长（旧版本会泄漏）
staged_files: "OrderedDict[str, Dict[str, Any]]" = OrderedDict()
STAGED_FILES_LIMIT = 200

# Recent transfer history (bounded)
transfer_history: List[Dict[str, Any]] = []
HISTORY_LIMIT = 200

# 正在进行的 PC->手机 传输进度: file_id -> {"sent": int, "total": int}
transfer_progress: Dict[str, Dict[str, int]] = {}


def _prune_staged_files() -> None:
    """超出上限时淘汰最旧的暂存文件，并清理对应临时文件。"""
    while len(staged_files) > STAGED_FILES_LIMIT:
        _, old = staged_files.popitem(last=False)
        try:
            p = old.get("path")
            if p and Path(p).exists():
                Path(p).unlink(missing_ok=True)
        except Exception:
            pass


def _cleanup_temp_send_dir() -> None:
    """启动时清空上次运行遗留的临时发送目录。"""
    temp_dir = DOWNLOADS_DIR / ".temp_send"
    try:
        if temp_dir.exists():
            for f in temp_dir.iterdir():
                try:
                    if f.is_file():
                        f.unlink(missing_ok=True)
                except Exception:
                    pass
    except Exception:
        pass


def _add_history(item: Dict[str, Any]) -> None:
    transfer_history.insert(0, item)
    if len(transfer_history) > HISTORY_LIMIT:
        del transfer_history[HISTORY_LIMIT:]


_cleanup_temp_send_dir()

def get_local_ips() -> List[str]:
    """Retrieve all valid LAN IPv4 addresses."""
    ips = []
    # Primary outbound IP test
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        primary = s.getsockname()[0]
        s.close()
        if primary and not primary.startswith('127.'):
            ips.append(primary)
    except Exception:
        pass

    for iface, snics in psutil.net_if_addrs().items():
        for snic in snics:
            if snic.family == socket.AF_INET:
                ip = snic.address
                if not ip.startswith('127.') and not ip.startswith('169.254.') and ip not in ips:
                    ips.append(ip)

    # Prioritize 192.168.* and 10.* and 172.16-31.*
    def ip_priority(ip_str: str) -> int:
        if ip_str.startswith("192.168."):
            return 0
        if ip_str.startswith("10."):
            return 1
        if ip_str.startswith("172."):
            return 2
        if ip_str.startswith("198.18."):
            return 9  # virtual/tun adapter
        return 5

    ips.sort(key=ip_priority)

    if not ips:
        ips.append('127.0.0.1')
    return ips

def generate_qr_base64(data: str) -> str:
    """Generate Base64 encoded PNG QR code."""
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=7,
        border=2,
    )
    qr.add_data(data)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#0F172A", back_color="#FFFFFF")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("utf-8")

async def broadcast_to_browsers(message: dict):
    """Notify all connected PC UI windows.

    每个发送都加超时，避免某个卡死的浏览器连接拖住整个事件循环（旧版本会让整个
    UI 表现为"卡住 / 掉线"）。
    """
    if not connected_browsers:
        return
    dead = []
    for ws in list(connected_browsers):
        try:
            await asyncio.wait_for(ws.send_json(message), timeout=3.0)
        except Exception:
            dead.append(ws)
    for ws in dead:
        if ws in connected_browsers:
            connected_browsers.remove(ws)

async def notify_android_clients(message: dict):
    """Send command to all connected Android phones."""
    if not connected_androids:
        return
    dead = []
    for client_id, ws in list(connected_androids.items()):
        try:
            await asyncio.wait_for(ws.send_text(json.dumps(message)), timeout=5.0)
        except Exception:
            dead.append(client_id)
    for cid in dead:
        connected_androids.pop(cid, None)

@app.get("/api/info")
def get_info(ip: Optional[str] = None):
    all_ips = get_local_ips()
    selected_ip = ip if ip in all_ips else all_ips[0]
    port = SERVER_PORT

    # Build QR payload
    qr_payload = {
        "type": "crosstransfer",
        "ip": selected_ip,
        "ips": all_ips,
        "port": port,
        "name": socket.gethostname(),
        "url": f"http://{selected_ip}:{port}/"
    }

    qr_image = generate_qr_base64(json.dumps(qr_payload))

    return {
        "hostname": socket.gethostname(),
        "version": APP_VERSION,
        "selected_ip": selected_ip,
        "all_ips": all_ips,
        "port": port,
        "download_dir": str(DOWNLOADS_DIR),
        "qr_image": qr_image,
        "qr_payload": qr_payload,
        "connected_phones": len(connected_androids),
        "history": transfer_history[:30]
    }


@app.get("/api/ping")
def ping():
    """轻量健康检查，手机端用于判定链路是否还活着。"""
    return {"status": "pong", "time": time.time(), "version": APP_VERSION}


@app.get("/api/status")
def status():
    """UI 轮询兜底：WebSocket 万一断线，前端也能靠这个恢复正确状态。"""
    return {
        "connected_phones": len(connected_androids),
        "browsers": len(connected_browsers),
        "history": transfer_history[:30],
        "version": APP_VERSION,
        "in_progress": [
            {"id": fid, **info} for fid, info in transfer_progress.items()
        ],
    }


def _safe_target_dir(raw: str) -> Optional[Path]:
    """把手机传来的目标目录解析成安全的绝对路径；非法或不可创建则返回 None。"""
    if not raw:
        return None
    try:
        p = Path(raw)
        if not p.is_absolute():
            return None
        p.mkdir(parents=True, exist_ok=True)
        if p.is_dir() and os.access(p, os.W_OK):
            return p
    except Exception:
        return None
    return None


@app.post("/api/upload")
async def upload_file(
    request: Request,
    file: UploadFile = File(...),
    target_dir: str = Form(default=""),
):
    """Receive file uploaded from Android phone or PC.

    v1.5.0: 真正支持 X-Target-Dir（旧版本完全忽略它，导致手机端指定的保存目录失效，
    文件一律落到 Downloads/CrossTransfer）。
    """
    try:
        original_name = Path(file.filename or "unnamed_file").name
        if not original_name or original_name in (".", ".."):
            original_name = "unnamed_file"

        # 目标目录优先级: 表单字段 > 请求头 > 默认下载目录
        raw_target = (target_dir or "").strip()
        if not raw_target:
            raw_target = (request.headers.get("x-target-dir") or "").strip()
        if raw_target:
            try:
                from urllib.parse import unquote
                raw_target = unquote(raw_target)
            except Exception:
                pass

        dest_dir = _safe_target_dir(raw_target) or DOWNLOADS_DIR

        target_path = dest_dir / original_name

        # Avoid name collision
        counter = 1
        stem = Path(original_name).stem
        suffix = Path(original_name).suffix
        while target_path.exists():
            target_path = dest_dir / f"{stem}_{counter}{suffix}"
            counter += 1

        # Stream save
        total_size = 0
        with open(target_path, "wb") as f:
            while chunk := await file.read(64 * 1024):
                f.write(chunk)
                total_size += len(chunk)

        item = {
            "id": str(uuid.uuid4()),
            "name": target_path.name,
            "size": total_size,
            "path": str(target_path.resolve()),
            "time": time.time(),
            "direction": "incoming",
            "source": "Android 手机"
        }
        _add_history(item)

        # Notify PC browser UI
        await broadcast_to_browsers({
            "type": "file_received",
            "file": item
        })

        return {"status": "ok", "file": item}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/api/send_to_phone")
async def send_to_phone(file: UploadFile = File(...), target_dir: str = Form(default="")):
    """PC user uploads a file to be pushed to Android."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备，请先用手机扫码！")

    file_id = str(uuid.uuid4())
    temp_dir = DOWNLOADS_DIR / ".temp_send"
    temp_dir.mkdir(parents=True, exist_ok=True)
    
    clean_name = Path(file.filename).name if file.filename else "unnamed_file"
    temp_file = temp_dir / f"{file_id}_{clean_name}"

    total_size = 0
    with open(temp_file, "wb") as f:
        while chunk := await file.read(64 * 1024):
            f.write(chunk)
            total_size += len(chunk)

    staged_files[file_id] = {
        "path": temp_file,
        "name": clean_name,
        "size": total_size,
        "target_dir": target_dir
    }
    _prune_staged_files()

    item = {
        "id": file_id,
        "name": clean_name,
        "size": total_size,
        "path": str(temp_file.resolve()),
        "time": time.time(),
        "direction": "outgoing",
        "source": "Windows 电脑"
    }
    _add_history(item)

    # Notify Android
    await notify_android_clients({
        "action": "send_file_to_phone",
        "id": file_id,
        "name": clean_name,
        "size": total_size,
        "target_dir": target_dir
    })

    # Notify PC UI
    await broadcast_to_browsers({
        "type": "file_sent",
        "file": item
    })

    return {"status": "ok", "file_id": file_id}

@app.post("/api/send_local_folder")
async def send_local_folder(payload: dict):
    """Package and stage a local folder directly from desktop app dialog."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备，请先用手机扫码！")
    
    folder_path = payload.get("path")
    target_dir = payload.get("target_dir", "")
    if not folder_path or not os.path.exists(folder_path):
        raise HTTPException(status_code=404, detail="本地文件夹不存在")
    
    p = Path(folder_path)
    if not p.is_dir():
        raise HTTPException(status_code=400, detail="所选路径不是文件夹")

    import zipfile
    folder_name = p.name
    zip_name = f"{folder_name}.zip"
    file_id = str(uuid.uuid4())
    temp_dir = DOWNLOADS_DIR / ".temp_send"
    temp_dir.mkdir(parents=True, exist_ok=True)
    temp_zip = temp_dir / f"{file_id}_{zip_name}"

    try:
        # 关键修复: zip 打包是同步阻塞的重活，直接在 async 里跑会把整个事件循环卡死，
        # 表现为手机端"连接中断"、PC UI 完全无响应。放到线程池里执行。
        def _build_zip() -> int:
            with zipfile.ZipFile(temp_zip, 'w', zipfile.ZIP_DEFLATED) as zf:
                for root, _, files in os.walk(folder_path):
                    for file in files:
                        full_p = os.path.join(root, file)
                        rel_p = os.path.relpath(full_p, folder_path)
                        zf.write(full_p, arcname=os.path.join(folder_name, rel_p))
            return temp_zip.stat().st_size

        total_size = await asyncio.get_event_loop().run_in_executor(None, _build_zip)

        staged_files[file_id] = {
            "path": temp_zip,
            "name": zip_name,
            "size": total_size,
            "target_dir": target_dir
        }
        _prune_staged_files()

        item = {
            "id": file_id,
            "name": zip_name,
            "size": total_size,
            "path": str(temp_zip.resolve()),
            "time": time.time(),
            "direction": "outgoing",
            "source": "Windows 电脑 (文件夹压缩包)"
        }
        _add_history(item)

        # Notify Android
        await notify_android_clients({
            "action": "send_file_to_phone",
            "id": file_id,
            "name": zip_name,
            "size": total_size,
            "target_dir": target_dir
        })

        # Notify PC UI
        await broadcast_to_browsers({
            "type": "file_sent",
            "file": item
        })

        return {"status": "ok", "file": item}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/download/{file_id}")
def download_staged_file(file_id: str):
    """Android calls this to download a file sent by PC."""
    info = staged_files.get(file_id)
    if not info or not info["path"].exists():
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(
        path=info["path"],
        filename=info["name"],
        media_type="application/octet-stream"
    )

@app.post("/api/open_folder")
def open_folder():
    """Open CrossTransfer download directory in Windows Explorer."""
    try:
        if sys.platform == "win32":
            os.startfile(str(DOWNLOADS_DIR))
        else:
            subprocess.Popen(["xdg-open", str(DOWNLOADS_DIR)])
        return {"status": "ok"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.post("/api/open_file")
def open_file(payload: dict):
    """Open specified file with default system application."""
    path = payload.get("path")
    if not path or not os.path.exists(path):
        raise HTTPException(status_code=404, detail="File not found")
    try:
        if sys.platform == "win32":
            os.startfile(path)
        else:
            subprocess.Popen(["xdg-open", path])
        return {"status": "ok"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.post("/api/disconnect")
async def disconnect_devices():
    """Manually disconnect all connected Android phones from PC."""
    await notify_android_clients({
        "action": "disconnect_peer",
        "reason": "PC端主动断开连接"
    })
    for cid, ws in list(connected_androids.items()):
        try:
            await ws.close(1000, "PC主动断开")
        except Exception:
            pass
    connected_androids.clear()
    await broadcast_to_browsers({
        "type": "phone_disconnected",
        "count": 0,
        "reason": "PC端已手动断开"
    })
    return {"status": "ok"}

@app.post("/api/request_phone_storage")
async def request_phone_storage():
    """Ask phone to send its storage file list."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接手机")
    await notify_android_clients({
        "action": "list_storage"
    })
    return {"status": "ok"}

@app.post("/api/report_phone_storage")
async def report_phone_storage(payload: dict):
    """Phone reports its storage files to PC."""
    files = payload.get("files", [])
    await broadcast_to_browsers({
        "type": "phone_storage_updated",
        "files": files
    })
    return {"status": "ok"}

@app.post("/api/request_phone_file")
async def request_phone_file(payload: dict):
    """Ask phone to push a specific file to PC."""
    file_path = payload.get("path")
    if not file_path:
        raise HTTPException(status_code=400, detail="缺少文件路径")
    await notify_android_clients({
        "action": "send_file_to_pc",
        "path": file_path
    })
    return {"status": "ok"}

@app.post("/api/trigger_phone_picker")
async def trigger_phone_picker():
    """Remotely request the phone to launch its storage file picker."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接手机")
    await notify_android_clients({
        "action": "open_file_picker"
    })
    return {"status": "ok"}

@app.post("/api/list_phone_dir")
async def list_phone_dir(payload: dict = None):
    """Request phone to list contents of a directory."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接手机")
    path = payload.get("path", "") if payload else ""
    await notify_android_clients({
        "action": "list_directory",
        "path": path
    })
    return {"status": "ok"}

@app.post("/api/request_storage_permission")
async def request_storage_permission():
    """Ask phone to pop open storage permission settings."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接手机")
    await notify_android_clients({
        "action": "request_storage_permission"
    })
    return {"status": "ok"}

def get_windows_clipboard_files() -> List[str]:
    """Retrieve list of files/directories currently copied in Windows clipboard (CF_HDROP)."""
    if sys.platform != "win32":
        return []
    CF_HDROP = 15
    files = []
    user32 = ctypes.windll.user32
    shell32 = ctypes.windll.shell32
    if not user32.OpenClipboard(None):
        return []
    try:
        h_drop = user32.GetClipboardData(CF_HDROP)
        if not h_drop:
            return []
        count = shell32.DragQueryFileW(h_drop, 0xFFFFFFFF, None, 0)
        buf = ctypes.create_unicode_buffer(1024)
        for i in range(count):
            length = shell32.DragQueryFileW(h_drop, i, buf, 1024)
            if length > 0:
                files.append(buf.value)
    except Exception as e:
        print(f"Clipboard read error: {e}")
    finally:
        user32.CloseClipboard()
    return files

@app.get("/api/clipboard_files")
def get_clipboard_items():
    """Get list of files currently in Windows clipboard."""
    files = get_windows_clipboard_files()
    items = []
    for fp in files:
        p = Path(fp)
        if p.exists():
            items.append({
                "name": p.name,
                "path": str(p.resolve()),
                "is_dir": p.is_dir(),
                "size": p.stat().st_size if p.is_file() else 0
            })
    return {"status": "ok", "files": items}

@app.post("/api/paste_clipboard")
async def paste_clipboard(payload: dict = None):
    """Pushes all files/folders in Windows clipboard to the phone."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备，请先用手机扫码！")
    
    target_dir = payload.get("target_dir", "") if payload else ""
    files = get_windows_clipboard_files()
    if not files:
        return {"status": "empty", "message": "电脑剪贴板中没有复制的文件或文件夹"}
    
    transferred = []
    for fp in files:
        p = Path(fp)
        if not p.exists():
            continue
        if p.is_file():
            file_id = str(uuid.uuid4())
            clean_name = p.name
            file_size = p.stat().st_size
            staged_files[file_id] = {
                "path": p,
                "name": clean_name,
                "size": file_size,
                "target_dir": target_dir
            }
            item = {
                "id": file_id,
                "name": clean_name,
                "size": file_size,
                "path": str(p.resolve()),
                "time": time.time(),
                "direction": "outgoing",
                "source": "Windows 剪贴板粘贴"
            }
            transfer_history.insert(0, item)
            await notify_android_clients({
                "action": "send_file_to_phone",
                "id": file_id,
                "name": clean_name,
                "size": file_size,
                "target_dir": target_dir
            })
            await broadcast_to_browsers({
                "type": "file_sent",
                "file": item
            })
            transferred.append(clean_name)
        elif p.is_dir():
            folder_name = p.name
            root_parent = p.parent
            walked_any = False
            for root, dirs, files_in_dir in os.walk(str(p)):
                walked_any = True
                rel_root = os.path.relpath(root, str(root_parent)).replace("\\", "/")
                curr_target = f"{target_dir}/{rel_root}" if target_dir else rel_root
                await notify_android_clients({
                    "action": "create_directory",
                    "path": curr_target
                })
                for fn in files_in_dir:
                    fpath = Path(root) / fn
                    fid = str(uuid.uuid4())
                    fsize = fpath.stat().st_size
                    staged_files[fid] = {
                        "path": fpath,
                        "name": fn,
                        "size": fsize,
                        "target_dir": curr_target
                    }
                    item = {
                        "id": fid,
                        "name": fn,
                        "size": fsize,
                        "path": str(fpath.resolve()),
                        "time": time.time(),
                        "direction": "outgoing",
                        "source": f"Windows 文件夹传输 ({folder_name})"
                    }
                    transfer_history.insert(0, item)
                    await notify_android_clients({
                        "action": "send_file_to_phone",
                        "id": fid,
                        "name": fn,
                        "size": fsize,
                        "target_dir": curr_target
                    })
                    await broadcast_to_browsers({
                        "type": "file_sent",
                        "file": item
                    })
            if not walked_any:
                empty_target = f"{target_dir}/{folder_name}" if target_dir else folder_name
                await notify_android_clients({
                    "action": "create_directory",
                    "path": empty_target
                })
            transferred.append(folder_name)
    
    return {"status": "ok", "transferred": transferred}

@app.post("/api/create_phone_dir")
async def create_phone_dir(payload: dict):
    """Remotely create directory on phone."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备")
    path = payload.get("path")
    if not path:
        raise HTTPException(status_code=400, detail="缺少目录路径")
    await notify_android_clients({
        "action": "create_directory",
        "path": path
    })
    return {"status": "ok"}

@app.post("/api/delete_phone_file")
async def delete_phone_file(payload: dict):
    """Remotely delete file or directory on phone."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备")
    path = payload.get("path")
    if not path:
        raise HTTPException(status_code=400, detail="缺少文件或目录路径")
    await notify_android_clients({
        "action": "delete_file",
        "path": path
    })
    return {"status": "ok"}

@app.post("/api/rename_phone_file")
async def rename_phone_file(payload: dict):
    """Remotely rename file or directory on phone."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备")
    path = payload.get("path")
    new_name = payload.get("new_name")
    if not path or not new_name:
        raise HTTPException(status_code=400, detail="缺少路径或新文件名")
    await notify_android_clients({
        "action": "rename_file",
        "path": path,
        "new_name": new_name
    })
    return {"status": "ok"}

@app.post("/api/copy_phone_file")
async def copy_phone_file(payload: dict):
    """Remotely copy file or directory on phone."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备")
    source_path = payload.get("source_path")
    target_dir = payload.get("target_dir")
    if not source_path or not target_dir:
        raise HTTPException(status_code=400, detail="缺少源路径或目标目录")
    await notify_android_clients({
        "action": "copy_file",
        "source_path": source_path,
        "target_dir": target_dir
    })
    return {"status": "ok"}

@app.post("/api/move_phone_file")
async def move_phone_file(payload: dict):
    """Remotely move/cut file or directory on phone."""
    if not connected_androids:
        raise HTTPException(status_code=400, detail="未连接任何安卓设备")
    source_path = payload.get("source_path")
    target_dir = payload.get("target_dir")
    if not source_path or not target_dir:
        raise HTTPException(status_code=400, detail="缺少源路径或目标目录")
    await notify_android_clients({
        "action": "move_file",
        "source_path": source_path,
        "target_dir": target_dir
    })
    return {"status": "ok"}

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    query_params = dict(websocket.query_params)
    device_type = query_params.get("device", "browser")
    model = query_params.get("model", "Android Device")
    client_ip = websocket.client.host if websocket.client else "未知IP"
    client_id = str(uuid.uuid4())

    if device_type == "android":
        connected_androids[client_id] = websocket
        # Notify PC browser UI that Android has joined
        await broadcast_to_browsers({
            "type": "phone_connected",
            "model": model,
            "ip": client_ip,
            "count": len(connected_androids)
        })
        # Send PC host name back to phone
        try:
            await websocket.send_text(json.dumps({
                "action": "device_info",
                "name": f"{socket.gethostname()} (Windows)",
                "os": "Windows"
            }))
        except Exception:
            pass
    else:
        connected_browsers.append(websocket)

    # 关键修复: 用接收超时兜底。手机突然断电/掉 WiFi 时 TCP 不会立刻报错，
    # 服务端会一直挂在一个永不到来的 receive 上，PC UI 就长期显示"已连接"却什么都做不了。
    # 客户端每 8-10 秒发一次 ping，因此 90 秒完全静默即可判定链路已死。
    RECV_TIMEOUT = 90.0

    try:
        while True:
            try:
                data = await asyncio.wait_for(websocket.receive_text(), timeout=RECV_TIMEOUT)
            except asyncio.TimeoutError:
                try:
                    await websocket.close(code=1001, reason="heartbeat timeout")
                except Exception:
                    pass
                break

            if data == "ping":
                try:
                    await websocket.send_text("pong")
                except Exception:
                    break
                continue
            if data == "pong":
                continue
            try:
                msg = json.loads(data)
                action = msg.get("action")
                if action == "phone_disconnect":
                    # Phone manually clicked disconnect
                    connected_androids.pop(client_id, None)
                    await broadcast_to_browsers({
                        "type": "phone_disconnected",
                        "count": len(connected_androids),
                        "reason": "手机端已手动断开"
                    })
                    break
                elif action == "storage_list":
                    files = msg.get("files", [])
                    await broadcast_to_browsers({
                        "type": "phone_storage_updated",
                        "files": files
                    })
                elif action == "directory_content":
                    await broadcast_to_browsers({
                        "type": "directory_content",
                        "current_path": msg.get("current_path", ""),
                        "parent_path": msg.get("parent_path", ""),
                        "root_path": msg.get("root_path", ""),
                        "has_permission": msg.get("has_permission", True),
                        "items": msg.get("items", [])
                    })
                elif action == "operation_result":
                    await broadcast_to_browsers({
                        "type": "operation_result",
                        "operation": msg.get("operation"),
                        "success": msg.get("success"),
                        "message": msg.get("message"),
                        "refresh_path": msg.get("refresh_path")
                    })
                elif action == "transfer_progress":
                    fid = msg.get("id")
                    if fid:
                        transfer_progress[fid] = {
                            "sent": int(msg.get("sent", 0) or 0),
                            "total": int(msg.get("total", 0) or 0),
                        }
                        if msg.get("done"):
                            transfer_progress.pop(fid, None)
                        await broadcast_to_browsers({
                            "type": "transfer_progress",
                            "id": fid,
                            "name": msg.get("name", ""),
                            "sent": msg.get("sent", 0),
                            "total": msg.get("total", 0),
                            "done": bool(msg.get("done"))
                        })
            except Exception:
                pass
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        if device_type == "android":
            connected_androids.pop(client_id, None)
            try:
                await broadcast_to_browsers({
                    "type": "phone_disconnected",
                    "count": len(connected_androids),
                    "reason": "手机连接已断开"
                })
            except Exception:
                pass
        else:
            if websocket in connected_browsers:
                connected_browsers.remove(websocket)

# Ensure stdout/stderr exist in windowed mode
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w")

# Mount static files
if getattr(sys, 'frozen', False):
    static_dir = Path(getattr(sys, '_MEIPASS', '')) / "static"
    if not static_dir.exists():
        static_dir = Path(sys.executable).parent / "_internal" / "static"
    if not static_dir.exists():
        static_dir = Path(sys.executable).parent / "static"
else:
    static_dir = Path(__file__).resolve().parent / "static"

app.mount("/", StaticFiles(directory=str(static_dir), html=True), name="static")

if __name__ == "__main__":
    import multiprocessing
    multiprocessing.freeze_support()
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=SERVER_PORT,
        timeout_keep_alive=3600,
        ws_ping_interval=20.0,
        ws_ping_timeout=90.0,
        log_level="warning"
    )
