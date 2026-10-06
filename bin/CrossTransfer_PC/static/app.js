// CrossTransfer PC Desktop App - v1.3.0
let currentIp = "";
let serverPort = 52020;
let ws = null;
let fileList = [];
let currentPhoneModel = "";
let isDeviceConnected = false;
let phoneFiles = [];

// DOM Elements
const selectIp = document.getElementById('selectIp');
const imgQr = document.getElementById('imgQr');
const qrOverlay = document.getElementById('qrOverlay');
const txtCurrentUrl = document.getElementById('txtCurrentUrl');
const btnCopyUrl = document.getElementById('btnCopyUrl');
const phoneStatusBadge = document.getElementById('phoneStatusBadge');
const txtDeviceName = document.getElementById('txtDeviceName');
const txtDeviceDesc = document.getElementById('txtDeviceDesc');
const statusIndicator = document.getElementById('statusIndicator');
const deviceActions = document.getElementById('deviceActions');
const btnEnterStorage = document.getElementById('btnEnterStorage');
const btnDisconnect = document.getElementById('btnDisconnect');

const dropzone = document.getElementById('dropzone');
const filePicker = document.getElementById('filePicker');
const folderPicker = document.getElementById('folderPicker');
const btnBrowseFiles = document.getElementById('btnBrowseFiles');
const btnBrowseFolder = document.getElementById('btnBrowseFolder');
const btnOpenFolder = document.getElementById('btnOpenFolder');
const progressCard = document.getElementById('progressCard');
const txtProgressTitle = document.getElementById('txtProgressTitle');
const txtProgressPercent = document.getElementById('txtProgressPercent');
const progressBarFill = document.getElementById('progressBarFill');
const txtProgressSpeed = document.getElementById('txtProgressSpeed');
const txtProgressDetails = document.getElementById('txtProgressDetails');
const historyList = document.getElementById('historyList');
const txtHistoryCount = document.getElementById('txtHistoryCount');
const emptyHint = document.getElementById('emptyHint');

// Modal Elements - Phone Storage
const phoneStorageModal = document.getElementById('phoneStorageModal');
const txtModalDeviceTitle = document.getElementById('txtModalDeviceTitle');
const btnCloseModal = document.getElementById('btnCloseModal');
const btnDismissModal = document.getElementById('btnDismissModal');
const btnRefreshPhoneStorage = document.getElementById('btnRefreshPhoneStorage');
const btnModalUploadToPhone = document.getElementById('btnModalUploadToPhone');
const btnModalUploadFolderToPhone = document.getElementById('btnModalUploadFolderToPhone');
const btnModalDisconnect = document.getElementById('btnModalDisconnect');
const phoneFileList = document.getElementById('phoneFileList');
const phoneStorageLoading = document.getElementById('phoneStorageLoading');
const txtPhoneStorageStats = document.getElementById('txtPhoneStorageStats');

const btnGoRoot = document.getElementById('btnGoRoot');
const btnGoParent = document.getElementById('btnGoParent');
const txtCurrentPath = document.getElementById('txtCurrentPath');
const permissionAlertBanner = document.getElementById('permissionAlertBanner');
const btnRequestPermission = document.getElementById('btnRequestPermission');
const btnTriggerRemotePicker = document.getElementById('btnTriggerRemotePicker');
const modalFolderFileInput = document.getElementById('modalFolderFileInput');
const modalFolderDirInput = document.getElementById('modalFolderDirInput');
const txtUploadTargetHint = document.getElementById('txtUploadTargetHint');
const btnModalPaste = document.getElementById('btnModalPaste');
const btnModalNewFolder = document.getElementById('btnModalNewFolder');
const explorerContextMenu = document.getElementById('explorerContextMenu');
const cmenuOpen = document.getElementById('cmenuOpen');
const cmenuDownload = document.getElementById('cmenuDownload');
const cmenuCopy = document.getElementById('cmenuCopy');
const cmenuCut = document.getElementById('cmenuCut');
const cmenuPaste = document.getElementById('cmenuPaste');
const cmenuRename = document.getElementById('cmenuRename');
const cmenuNewFolder = document.getElementById('cmenuNewFolder');
const cmenuDelete = document.getElementById('cmenuDelete');

let selectedPhoneItem = null;
let phoneClipboard = null; // { action: 'copy'|'move', path, name, is_dir }

// Modal Elements - Folder Upload Choice
const folderChoiceModal = document.getElementById('folderChoiceModal');
const txtFolderChoiceTitle = document.getElementById('txtFolderChoiceTitle');
const txtFolderChoiceSubtitle = document.getElementById('txtFolderChoiceSubtitle');
const btnCloseFolderChoiceModal = document.getElementById('btnCloseFolderChoiceModal');
const txtFolderInfoName = document.getElementById('txtFolderInfoName');
const txtFolderInfoStats = document.getElementById('txtFolderInfoStats');
const optZip = document.getElementById('optZip');
const optDirect = document.getElementById('optDirect');
const btnCancelFolderChoice = document.getElementById('btnCancelFolderChoice');
const btnConfirmFolderChoice = document.getElementById('btnConfirmFolderChoice');

// Full-Window Drag & Drop Overlay
const globalDragOverlay = document.getElementById('globalDragOverlay');
const dragOverlayTitle = document.getElementById('dragOverlayTitle');
const dragOverlaySub = document.getElementById('dragOverlaySub');
const dragOverlayTarget = document.getElementById('dragOverlayTarget');

let currentDirectoryPath = "/storage/emulated/0";
let currentParentPath = null;
let currentRootPath = "/storage/emulated/0";
let hasStoragePermission = true;
let isDirectoryLoading = false;
let isPhoneModalOpened = false;
let storageFetchTimeout = null;

// Format file size
function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
}

function getFileIcon(name) {
    if (!name) return '📄';
    const ext = name.split('.').pop().toLowerCase();
    switch (ext) {
        case 'zip': case 'rar': case '7z': case 'tar': case 'gz':
            return '📦';
        case 'png': case 'jpg': case 'jpeg': case 'webp': case 'gif': case 'bmp':
            return '🖼️';
        case 'mp4': case 'mkv': case 'mov': case 'avi': case 'flv':
            return '🎬';
        case 'mp3': case 'wav': case 'flac': case 'aac': case 'ogg':
            return '🎵';
        case 'pdf':
            return '📕';
        case 'doc': case 'docx':
            return '📘';
        case 'xls': case 'xlsx':
            return '📊';
        case 'ppt': case 'pptx':
            return '📙';
        case 'apk':
            return '🤖';
        case 'txt': case 'md': case 'json': case 'log':
            return '📝';
        default:
            return '📄';
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function showNotification(msg, duration = 3000) {
    console.log(msg);
    let container = document.getElementById('toastContainer');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toastContainer';
        container.style.cssText = 'position:fixed;bottom:24px;right:24px;z-index:99999;display:flex;flex-direction:column;gap:10px;pointer-events:none;';
        document.body.appendChild(container);
    }
    const toast = document.createElement('div');
    toast.style.cssText = 'background:#1e293b;color:#f8fafc;padding:12px 20px;border-radius:10px;font-size:13px;font-weight:600;box-shadow:0 8px 24px rgba(0,0,0,0.2);display:flex;align-items:center;gap:8px;pointer-events:auto;transition:all 0.3s ease;border:1px solid #334155;';
    toast.textContent = msg;
    container.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(10px)';
        setTimeout(() => toast.remove(), 300);
    }, duration);
}

// Fetch Server Status and QR code
async function loadServerInfo(overrideIp = null) {
    try {
        const query = overrideIp ? `?ip=${encodeURIComponent(overrideIp)}` : '';
        const res = await fetch(`/api/info${query}`);
        const data = await res.json();

        currentIp = data.selected_ip;
        serverPort = data.port;

        // Render IP Options
        selectIp.innerHTML = '';
        data.all_ips.forEach(ip => {
            const opt = document.createElement('option');
            opt.value = ip;
            opt.textContent = ip + (ip === data.selected_ip ? ' (推荐局域网)' : '');
            if (ip === data.selected_ip) opt.selected = true;
            selectIp.appendChild(opt);
        });

        // Set QR and URL
        imgQr.src = data.qr_image;
        qrOverlay.style.display = 'none';
        const urlStr = `http://${currentIp}:${serverPort}/`;
        txtCurrentUrl.textContent = urlStr;

        // Update phone status
        if (data.connected_phones > 0) {
            updateDeviceConnected("Android 手机");
        } else {
            updateDeviceDisconnected();
        }

        // Render history if available
        if (data.history && data.history.length > 0) {
            data.history.forEach(item => addHistoryItem(item, false));
        }

    } catch (e) {
        console.error("加载服务器状态失败:", e);
    }
}

function updateDeviceConnected(model, autoOpenModal = true) {
    isDeviceConnected = true;
    currentPhoneModel = model || "Android 手机";
    phoneStatusBadge.textContent = "已连接";
    phoneStatusBadge.className = "badge connected";
    txtDeviceName.textContent = `已连接: ${currentPhoneModel}`;
    txtDeviceDesc.textContent = "双向通道畅通，点击下方进入手机存储";
    statusIndicator.className = "status-indicator online";
    deviceActions.style.display = 'flex';

    txtModalDeviceTitle.textContent = `手机存储管理 (${currentPhoneModel})`;

    if (autoOpenModal && !isPhoneModalOpened) {
        isPhoneModalOpened = true;
        openPhoneStorageModal();
    }
}

function updateDeviceDisconnected(reason = "请打开安卓APP扫码互联") {
    isDeviceConnected = false;
    currentPhoneModel = "";
    isPhoneModalOpened = false;
    if (storageFetchTimeout) clearTimeout(storageFetchTimeout);
    phoneStatusBadge.textContent = "等待连接";
    phoneStatusBadge.className = "badge";
    txtDeviceName.textContent = "尚未连接手机";
    txtDeviceDesc.textContent = reason;
    statusIndicator.className = "status-indicator";
    deviceActions.style.display = 'none';
    closePhoneStorageModal();
}

function openPhoneStorageModal() {
    phoneStorageModal.style.display = 'flex';
    requestPhoneDirectory(currentDirectoryPath || "/storage/emulated/0");
}

function closePhoneStorageModal() {
    phoneStorageModal.style.display = 'none';
}

// Request phone to list directory
async function requestPhoneDirectory(path = "") {
    isDirectoryLoading = true;
    phoneFileList.innerHTML = `
        <div class="loading-hint">
            <div style="font-size: 28px; margin-bottom: 8px;">⏳</div>
            <div>正在读取手机目录: <b>${escapeHtml(path.split('/').pop() || "内部存储根目录")}</b> ...</div>
        </div>
    `;
    txtPhoneStorageStats.textContent = "正在读取中...";

    if (storageFetchTimeout) clearTimeout(storageFetchTimeout);
    storageFetchTimeout = setTimeout(() => {
        if (isDirectoryLoading) {
            renderPhoneStorageReadyFallback();
        }
    }, 4000);

    try {
        await fetch('/api/list_phone_dir', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        });
    } catch (e) {
        phoneFileList.innerHTML = `<div class="loading-hint" style="color: #ef4444;">请求目录失败: ${e.message}</div>`;
    }
}

function renderPhoneStorageReadyFallback() {
    isDirectoryLoading = false;
    txtPhoneStorageStats.textContent = "存储通道已就绪";
    phoneFileList.innerHTML = `
        <div class="loading-hint">
            <div style="font-size: 36px; margin-bottom: 8px;">📱📂</div>
            <div style="font-weight: 700; color: #0f172a; font-size: 15px; margin-bottom: 6px;">手机存储通道已连接</div>
            <div style="color: #64748b; font-size: 13px; max-width: 520px; margin: 0 auto 16px; line-height: 1.5;">
                已与手机建立局域网高速通道。您可以直接拖拽文件或文件夹到本窗口，或点击下方常用目录进入：
            </div>
            <div style="display: flex; justify-content: center; gap: 10px; flex-wrap: wrap;">
                <button class="btn-primary" onclick="requestPhoneDirectory('/storage/emulated/0/Download')">📥 进入下载目录 (Download)</button>
                <button class="btn-secondary" onclick="triggerPhoneFilePicker()">📲 在手机端唤起存储选择器</button>
            </div>
        </div>
    `;
}

window.triggerPhoneFilePicker = async function() {
    try {
        await fetch('/api/trigger_phone_picker', { method: 'POST' });
        showNotification("已向手机发出指令，请在手机屏幕上选择文件");
    } catch (e) {
        alert("唤起失败: " + e);
    }
};

window.enterPhoneFolder = function(encodedPath) {
    const path = decodeURIComponent(encodedPath);
    requestPhoneDirectory(path);
};

function selectItem(item, rowElement) {
    selectedPhoneItem = item;
    document.querySelectorAll('.phone-explorer-row').forEach(r => r.classList.remove('selected'));
    if (rowElement) {
        rowElement.classList.add('selected');
    }
}

function clearSelection() {
    selectedPhoneItem = null;
    document.querySelectorAll('.phone-explorer-row').forEach(r => r.classList.remove('selected'));
}

function hideContextMenu() {
    if (explorerContextMenu) {
        explorerContextMenu.style.display = 'none';
    }
}

function showContextMenu(x, y, item) {
    if (!explorerContextMenu) return;
    const menuWidth = 200;
    const menuHeight = 260;
    const posX = (x + menuWidth > window.innerWidth) ? (window.innerWidth - menuWidth - 10) : x;
    const posY = (y + menuHeight > window.innerHeight) ? (window.innerHeight - menuHeight - 10) : y;
    explorerContextMenu.style.left = `${posX}px`;
    explorerContextMenu.style.top = `${posY}px`;
    explorerContextMenu.style.display = 'block';

    if (item) {
        cmenuOpen.style.display = 'flex';
        cmenuOpen.textContent = item.is_dir ? "📂 进入文件夹" : "⬇️ 下载到电脑";
        cmenuDownload.style.display = item.is_dir ? 'none' : 'flex';
        cmenuCopy.classList.remove('disabled');
        cmenuCut.classList.remove('disabled');
        cmenuRename.classList.remove('disabled');
        cmenuDelete.classList.remove('disabled');
    } else {
        cmenuOpen.style.display = 'none';
        cmenuDownload.style.display = 'none';
        cmenuCopy.classList.add('disabled');
        cmenuCut.classList.add('disabled');
        cmenuRename.classList.add('disabled');
        cmenuDelete.classList.add('disabled');
    }
}

async function createPhoneDirectoryRemote(path) {
    try {
        const res = await fetch('/api/create_phone_dir', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        });
        return await res.json();
    } catch (e) {
        console.error("创建目录失败:", e);
    }
}

async function deletePhoneFileRemote(path, isDir) {
    const itemType = isDir ? "文件夹及其中所有内容" : "文件";
    const name = path.split('/').filter(Boolean).pop();
    if (!confirm(`确定要从手机中彻底删除此${itemType}【${name}】吗？`)) return;
    try {
        const res = await fetch('/api/delete_phone_file', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        });
        const data = await res.json();
        if (data.status === 'ok') {
            showNotification(`已发送删除请求: ${name}`);
        }
    } catch (e) {
        alert("删除请求失败: " + e);
    }
}

async function renamePhoneFileRemote(path, currentName) {
    const newName = prompt(`请输入新的名称:`, currentName);
    if (!newName || newName.trim() === '' || newName.trim() === currentName) return;
    try {
        const res = await fetch('/api/rename_phone_file', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path, new_name: newName.trim() })
        });
        const data = await res.json();
        if (data.status === 'ok') {
            showNotification(`已发送重命名请求: ${newName.trim()}`);
        }
    } catch (e) {
        alert("重命名失败: " + e);
    }
}

function createNewFolderPrompt() {
    const name = prompt("请输入新建文件夹名称:", "新建文件夹");
    if (!name || name.trim() === '') return;
    const targetPath = `${currentDirectoryPath}/${name.trim()}`;
    createPhoneDirectoryRemote(targetPath);
    showNotification(`已发送新建文件夹请求: ${name.trim()}`);
}

async function executePasteAction(targetDir) {
    targetDir = targetDir || currentDirectoryPath;

    // 1. If phoneClipboard has a phone item
    if (phoneClipboard && phoneClipboard.path) {
        const isCut = phoneClipboard.action === 'move';
        const url = isCut ? '/api/move_phone_file' : '/api/copy_phone_file';
        try {
            showNotification(`正在${isCut ? '移动' : '复制'}手机项目: ${phoneClipboard.name} ...`);
            const res = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    source_path: phoneClipboard.path,
                    target_dir: targetDir
                })
            });
            const data = await res.json();
            if (data.status === 'ok') {
                if (isCut) {
                    phoneClipboard = null;
                }
            }
            return;
        } catch (e) {
            alert("手机文件操作失败: " + e);
            return;
        }
    }

    // 2. Otherwise paste from Windows PC clipboard
    try {
        showNotification("正在读取电脑剪贴板并粘贴到手机当前目录...");
        const res = await fetch('/api/paste_clipboard', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ target_dir: targetDir })
        });
        const data = await res.json();
        if (data.status === 'ok') {
            const count = data.transferred ? data.transferred.length : 0;
            if (count > 0) {
                showNotification(`📋 成功从电脑剪贴板粘贴 ${count} 个项目到手机！`);
                setTimeout(() => requestPhoneDirectory(currentDirectoryPath), 800);
            } else {
                showNotification("电脑剪贴板中未找到复制的文件或文件夹");
            }
        } else if (data.status === 'empty') {
            showNotification("⚠️ 电脑剪贴板为空，请先在电脑上复制(Ctrl+C)文件或文件夹！");
        } else {
            alert(data.message || data.detail || "粘贴失败");
        }
    } catch (e) {
        alert("粘贴电脑剪贴板失败: " + e);
    }
}

// Render directory content
function renderDirectoryContent(data) {
    isDirectoryLoading = false;
    if (storageFetchTimeout) clearTimeout(storageFetchTimeout);

    currentDirectoryPath = data.current_path || "/storage/emulated/0";
    currentParentPath = data.parent_path || null;
    currentRootPath = data.root_path || "/storage/emulated/0";
    hasStoragePermission = data.has_permission !== false;
    selectedPhoneItem = null;

    // Update path display
    txtCurrentPath.textContent = currentDirectoryPath;
    txtCurrentPath.title = currentDirectoryPath;
    if (txtUploadTargetHint) {
        const lastPart = currentDirectoryPath.split('/').filter(Boolean).pop() || "内部存储";
        txtUploadTargetHint.textContent = `(上传目标: ${lastPart})`;
    }

    // Update back button state
    btnGoParent.disabled = !currentParentPath;

    // Update permission warning
    if (permissionAlertBanner) {
        permissionAlertBanner.style.display = hasStoragePermission ? 'none' : 'flex';
    }

    const items = data.items || [];
    phoneFileList.innerHTML = '';

    if (items.length === 0) {
        phoneFileList.innerHTML = `
            <div class="loading-hint">
                <div style="font-size: 36px; margin-bottom: 8px;">📂</div>
                <div style="font-weight: 600; color: #334155; font-size: 14px;">此文件夹暂无文件</div>
                <div style="font-size: 12px; color: #94a3b8; margin-top: 6px;">可按 Ctrl+V 直接粘贴电脑文件，或拖拽文件/文件夹存入此目录</div>
            </div>
        `;
        txtPhoneStorageStats.textContent = "空文件夹";
        return;
    }

    let dirCount = 0;
    let fileCount = 0;

    items.forEach(item => {
        const row = document.createElement('div');
        row.className = `phone-explorer-row ${item.is_dir ? 'is-directory' : 'is-file'}`;
        row.setAttribute('data-path', item.path);

        const icon = item.is_dir ? '📁' : getFileIcon(item.name);
        const dateStr = item.modified ? new Date(item.modified * 1000).toLocaleString([], { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-';
        const sizeStr = item.is_dir ? (item.count !== undefined ? `${item.count} 项` : '文件夹') : formatBytes(item.size);

        const actionBtn = item.is_dir 
            ? `<button class="btn-enter-folder" onclick="event.stopPropagation(); enterPhoneFolder('${encodeURIComponent(item.path)}')">进入 ➔</button>`
            : `<button class="btn-download-file" onclick="event.stopPropagation(); pullFileFromPhone('${encodeURIComponent(item.path)}')">⬇️ 下载到电脑</button>`;

        row.innerHTML = `
            <div class="col-name" title="${item.path}">
                <span class="item-icon">${icon}</span>
                <span class="row-name-text">${escapeHtml(item.name)}</span>
            </div>
            <div class="col-size">${sizeStr}</div>
            <div class="col-time">${dateStr}</div>
            <div class="col-action">${actionBtn}</div>
        `;

        // Left click: select
        row.addEventListener('click', (e) => {
            selectItem(item, row);
            hideContextMenu();
        });

        // Double click: open folder
        if (item.is_dir) {
            row.addEventListener('dblclick', () => {
                enterPhoneFolder(encodeURIComponent(item.path));
            });
            dirCount++;
        } else {
            fileCount++;
        }

        // Right click: context menu
        row.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            e.stopPropagation();
            selectItem(item, row);
            showContextMenu(e.clientX, e.clientY, item);
        });

        phoneFileList.appendChild(row);
    });

    txtPhoneStorageStats.textContent = `共 ${dirCount} 个文件夹，${fileCount} 个文件`;
}

// Request phone to push file to PC
window.pullFileFromPhone = async function(encodedPath) {
    const path = decodeURIComponent(encodedPath);
    try {
        const res = await fetch('/api/request_phone_file', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        });
        const data = await res.json();
        if (data.status === 'ok') {
            showNotification("已向手机发出取件请求，正在开始传输...");
        } else {
            alert("请求文件失败: " + data.detail);
        }
    } catch (e) {
        alert("网络请求失败: " + e);
    }
};

// Disconnect from PC side
async function disconnectDevice() {
    if (!confirm("确定要断开与手机的连接吗？")) return;
    try {
        await fetch('/api/disconnect', { method: 'POST' });
        updateDeviceDisconnected("PC 端已主动断开连接");
        showNotification("已主动断开与手机的互联。");
    } catch (e) {
        alert("断开失败: " + e);
    }
}

let pcWsPingTimer = null;

// Setup WebSocket
function initWebSocket() {
    const loc = window.location;
    const wsProto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${wsProto}//${loc.host}/ws?device=browser`;

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log("PC UI WebSocket已连接");
        if (pcWsPingTimer) clearInterval(pcWsPingTimer);
        pcWsPingTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send("ping");
            }
        }, 10000);
    };

    ws.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            if (msg.type === 'phone_connected') {
                updateDeviceConnected(msg.model || "Android 手机", true);
                showNotification(`📱 安卓设备 (${msg.model || '手机'}) 已成功互联！`);
            } else if (msg.type === 'phone_disconnected') {
                if (msg.count === 0) {
                    updateDeviceDisconnected(msg.reason || "手机连接已断开");
                    showNotification(`🔌 ${msg.reason || "手机已断开连接"}`);
                }
            } else if (msg.type === 'directory_content') {
                renderDirectoryContent(msg);
            } else if (msg.type === 'phone_storage_updated') {
                if (msg.files && msg.files.length > 0) {
                    renderDirectoryContent({
                        current_path: "/storage/emulated/0/Download",
                        parent_path: "/storage/emulated/0",
                        root_path: "/storage/emulated/0",
                        has_permission: true,
                        items: msg.files.map(f => ({
                            name: f.name,
                            path: f.path,
                            is_dir: false,
                            size: f.size,
                            modified: f.modified
                        }))
                    });
                }
            } else if (msg.type === 'file_received') {
                addHistoryItem(msg.file, true);
                showNotification(`📥 收到来自手机的文件: ${msg.file.name}`);
            } else if (msg.type === 'file_sent') {
                addHistoryItem(msg.file, true);
            } else if (msg.type === 'operation_result') {
                if (msg.message) {
                    showNotification(msg.success ? `✅ ${msg.message}` : `❌ ${msg.message}`);
                }
                if (msg.refresh_path) {
                    requestPhoneDirectory(msg.refresh_path);
                }
            }
        } catch (e) {
            console.error("WS消息解析错误:", e);
        }
    };

    ws.onclose = () => {
        setTimeout(initWebSocket, 2000);
    };
}

// Single file upload via XHR with live speed and progress
function sendSingleFile(file, targetDir = "", progressPrefix = "") {
    return new Promise((resolve) => {
        const xhr = new XMLHttpRequest();
        const formData = new FormData();
        formData.append("file", file);
        if (targetDir) {
            formData.append("target_dir", targetDir);
        }

        progressCard.style.display = 'block';
        txtProgressTitle.textContent = `${progressPrefix}正在发送: ${file.name}`;
        txtProgressPercent.textContent = '0%';
        progressBarFill.style.width = '0%';
        txtProgressSpeed.textContent = '准备中...';
        txtProgressDetails.textContent = `0 MB / ${formatBytes(file.size)}`;

        let lastLoaded = 0;
        let lastTime = Date.now();

        xhr.upload.onprogress = (e) => {
            if (e.lengthComputable) {
                const percent = Math.round((e.loaded / e.total) * 100);
                progressBarFill.style.width = percent + '%';
                txtProgressPercent.textContent = percent + '%';

                const now = Date.now();
                const deltaT = (now - lastTime) / 1000;
                if (deltaT >= 0.25 || e.loaded === e.total) {
                    const speed = (e.loaded - lastLoaded) / deltaT;
                    txtProgressSpeed.textContent = `传输速度: ${formatBytes(speed)}/s`;
                    txtProgressDetails.textContent = `${formatBytes(e.loaded)} / ${formatBytes(e.total)}`;
                    lastLoaded = e.loaded;
                    lastTime = now;
                }
            }
        };

        xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                showNotification(`✅ 文件 ${file.name} 已成功推送给手机！`);
            } else {
                let err = "发送失败";
                try {
                    const res = JSON.parse(xhr.responseText);
                    err = res.detail || err;
                } catch (_) {}
                alert(`发送错误: ${err}`);
            }
            resolve();
        };

        xhr.onerror = () => {
            alert(`网络传输错误: 发送 ${file.name} 失败`);
            resolve();
        };

        xhr.open("POST", "/api/send_to_phone");
        xhr.send(formData);
    });
}

// Batch files sending with overall count
async function sendBatchFiles(files, targetDir = "") {
    if (!files || files.length === 0) return;
    const total = files.length;
    for (let i = 0; i < total; i++) {
        const file = files[i];
        const prefix = total > 1 ? `[${i + 1}/${total}] ` : '';
        await sendSingleFile(file, targetDir, prefix);
    }
    progressCard.style.display = 'none';

    // If modal is open, refresh directory
    if (phoneStorageModal && phoneStorageModal.style.display !== 'none') {
        setTimeout(() => {
            requestPhoneDirectory(currentDirectoryPath);
        }, 1000);
    }
}

// Package folder into a ZIP blob via JSZip and send
async function packageFolderToZipAndSend(folderName, items, targetDir = "", subDirs = []) {
    if (typeof JSZip === 'undefined') {
        alert("正在加载压缩模块，请稍候再试。");
        return;
    }

    const zip = new JSZip();
    const folder = zip.folder(folderName);

    progressCard.style.display = 'block';
    txtProgressTitle.textContent = `正在打包压缩文件夹: ${folderName}.zip ...`;
    txtProgressPercent.textContent = '0%';
    progressBarFill.style.width = '0%';
    txtProgressSpeed.textContent = '正在读取文件...';

    // Add empty sub-directories if any
    for (const sub of subDirs) {
        folder.folder(sub);
    }

    // Add each file with its relative path
    for (const item of items) {
        folder.file(item.relativePath, item.file);
    }

    txtProgressSpeed.textContent = '正在执行 DEFLATE 高速压缩...';

    const zipBlob = await zip.generateAsync({
        type: 'blob',
        compression: 'DEFLATE',
        compressionOptions: { level: 6 }
    }, (meta) => {
        const percent = Math.round(meta.percent);
        progressBarFill.style.width = percent + '%';
        txtProgressPercent.textContent = percent + '%';
        if (meta.currentFile) {
            txtProgressDetails.textContent = `处理中: ${meta.currentFile.split('/').pop()}`;
        }
    });

    const zipFileName = `${folderName}.zip`;
    const zipFile = new File([zipBlob], zipFileName, { type: 'application/zip' });

    await sendSingleFile(zipFile, targetDir, "【压缩包】");
    progressCard.style.display = 'none';

    if (phoneStorageModal && phoneStorageModal.style.display !== 'none') {
        setTimeout(() => {
            requestPhoneDirectory(currentDirectoryPath);
        }, 1000);
    }
}

// Send folder preserving hierarchy
async function sendFolderPreservingHierarchy(folderName, items, targetDir = "", subDirs = []) {
    // First, create the root folder and all sub-folders on phone
    const rootTarget = targetDir ? `${targetDir}/${folderName}` : folderName;
    await createPhoneDirectoryRemote(rootTarget);
    for (const sub of subDirs) {
        await createPhoneDirectoryRemote(`${rootTarget}/${sub}`);
    }

    const total = items.length;
    for (let i = 0; i < total; i++) {
        const item = items[i];
        let destDir = rootTarget;
        if (item.parentPath) {
            destDir = `${rootTarget}/${item.parentPath}`;
        }
        const prefix = `[文件夹 ${i + 1}/${total}] `;
        await sendSingleFile(item.file, destDir, prefix);
    }
    progressCard.style.display = 'none';

    if (phoneStorageModal && phoneStorageModal.style.display !== 'none') {
        setTimeout(() => {
            requestPhoneDirectory(currentDirectoryPath);
        }, 1000);
    }
}

// Choice Modal for Folder Transfer Strategy
function showFolderChoiceDialog(folderName, items, targetDir = "", subDirs = []) {
    return new Promise((resolve) => {
        let chosenMode = "zip"; // default recommended

        txtFolderInfoName.textContent = folderName;
        const totalSize = items.reduce((acc, x) => acc + x.file.size, 0);
        txtFolderInfoStats.textContent = `共 ${items.length} 个文件 • ${formatBytes(totalSize)}`;

        // Reset option styles
        optZip.className = 'folder-option-card active';
        optDirect.className = 'folder-option-card';

        optZip.onclick = () => {
            chosenMode = 'zip';
            optZip.className = 'folder-option-card active';
            optDirect.className = 'folder-option-card';
        };

        optDirect.onclick = () => {
            chosenMode = 'direct';
            optDirect.className = 'folder-option-card active';
            optZip.className = 'folder-option-card';
        };

        const cleanup = () => {
            folderChoiceModal.style.display = 'none';
            btnConfirmFolderChoice.onclick = null;
            btnCancelFolderChoice.onclick = null;
            btnCloseFolderChoiceModal.onclick = null;
        };

        btnConfirmFolderChoice.onclick = async () => {
            cleanup();
            if (chosenMode === 'zip') {
                await packageFolderToZipAndSend(folderName, items, targetDir, subDirs);
            } else {
                await sendFolderPreservingHierarchy(folderName, items, targetDir, subDirs);
            }
            resolve(true);
        };

        btnCancelFolderChoice.onclick = () => {
            cleanup();
            resolve(false);
        };

        btnCloseFolderChoiceModal.onclick = () => {
            cleanup();
            resolve(false);
        };

        folderChoiceModal.style.display = 'flex';
    });
}

// HTML5 Recursive DataTransfer Parser for Files and Folders
async function parseDataTransfer(dataTransfer) {
    const singleFiles = [];
    const folderGroups = {}; // folderName -> { items: [], subDirs: Set }

    async function readAllEntries(dirReader) {
        let entries = [];
        let readNext = async () => {
            return new Promise((resolve, reject) => {
                dirReader.readEntries((batch) => {
                    if (!batch || batch.length === 0) {
                        resolve(entries);
                    } else {
                        entries = entries.concat(batch);
                        readNext().then(resolve).catch(reject);
                    }
                }, reject);
            });
        };
        return await readNext();
    }

    async function traverseEntry(entry, currentPath = '', rootFolderName = '') {
        if (entry.isFile) {
            const file = await new Promise((res, rej) => entry.file(res, rej));
            if (rootFolderName) {
                if (!folderGroups[rootFolderName]) {
                    folderGroups[rootFolderName] = { items: [], subDirs: new Set() };
                }
                folderGroups[rootFolderName].items.push({
                    file: file,
                    relativePath: currentPath ? `${currentPath}/${file.name}` : file.name,
                    parentPath: currentPath
                });
            } else {
                singleFiles.push(file);
            }
        } else if (entry.isDirectory) {
            const curRoot = rootFolderName || entry.name;
            if (!folderGroups[curRoot]) {
                folderGroups[curRoot] = { items: [], subDirs: new Set() };
            }
            const nextPath = rootFolderName ? (currentPath ? `${currentPath}/${entry.name}` : entry.name) : '';
            if (nextPath) {
                folderGroups[curRoot].subDirs.add(nextPath);
            }
            const dirReader = entry.createReader();
            const entries = await readAllEntries(dirReader);
            for (const sub of entries) {
                await traverseEntry(sub, nextPath, curRoot);
            }
        }
    }

    if (dataTransfer.items && dataTransfer.items.length > 0 && dataTransfer.items[0].webkitGetAsEntry) {
        for (let i = 0; i < dataTransfer.items.length; i++) {
            const item = dataTransfer.items[i];
            if (item.kind === 'file') {
                const entry = item.webkitGetAsEntry();
                if (entry) {
                    await traverseEntry(entry);
                }
            }
        }
    } else if (dataTransfer.files && dataTransfer.files.length > 0) {
        for (let i = 0; i < dataTransfer.files.length; i++) {
            singleFiles.push(dataTransfer.files[i]);
        }
    }

    return { singleFiles, folderGroups };
}

// Handle all dropped items
async function handleDroppedItems(dataTransfer, targetDir = "") {
    try {
        const { singleFiles, folderGroups } = await parseDataTransfer(dataTransfer);

        const hasFiles = singleFiles.length > 0;
        const folderNames = Object.keys(folderGroups);
        const hasFolders = folderNames.length > 0;

        if (!hasFiles && !hasFolders) {
            alert("未检测到有效的文件或文件夹。");
            return;
        }

        // 1. Send single files directly
        if (hasFiles) {
            await sendBatchFiles(singleFiles, targetDir);
        }

        // 2. Prompt or process each dropped folder
        for (const name of folderNames) {
            const folderData = folderGroups[name];
            const items = folderData.items;
            const subDirs = Array.from(folderData.subDirs || []);

            // If empty folder (no files inside)
            if (items.length === 0) {
                const rootTarget = targetDir ? `${targetDir}/${name}` : name;
                await createPhoneDirectoryRemote(rootTarget);
                for (const sub of subDirs) {
                    await createPhoneDirectoryRemote(`${rootTarget}/${sub}`);
                }
                showNotification(`📁 已在手机端创建空文件夹: ${name}`);
                setTimeout(() => requestPhoneDirectory(currentDirectoryPath), 800);
            } else {
                await showFolderChoiceDialog(name, items, targetDir, subDirs);
            }
        }
    } catch (e) {
        console.error("处理拖拽项失败:", e);
        alert("处理拖拽文件失败: " + e);
    }
}

// Handle folder selected via HTML5 webkitdirectory input
async function handleDirectoryInput(fileListInput, targetDir = "") {
    if (!fileListInput || fileListInput.length === 0) return;

    const folderGroups = {};
    for (let i = 0; i < fileListInput.length; i++) {
        const file = fileListInput[i];
        // webkitRelativePath format: "folderName/sub/file.ext"
        const relPath = file.webkitRelativePath || file.name;
        const parts = relPath.split('/');
        const rootFolder = parts[0] || "folder";
        const innerPath = parts.slice(1, -1).join('/');
        const subRelPath = parts.slice(1).join('/');

        if (!folderGroups[rootFolder]) folderGroups[rootFolder] = [];
        folderGroups[rootFolder].push({
            file: file,
            relativePath: subRelPath || file.name,
            parentPath: innerPath
        });
    }

    for (const [name, items] of Object.entries(folderGroups)) {
        await showFolderChoiceDialog(name, items, targetDir);
    }
}

// Add history row
function addHistoryItem(item, prepend = true) {
    if (fileList.some(f => f.id === item.id)) return;
    fileList.push(item);
    emptyHint.style.display = 'none';

    const div = document.createElement('div');
    div.className = 'history-item';
    const timeStr = new Date(item.time * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const dirIcon = item.direction === 'incoming' ? '📥 来自手机' : '📤 发送给手机';

    div.innerHTML = `
        <div class="item-icon">${getFileIcon(item.name)}</div>
        <div class="item-info">
            <div class="item-name" title="${item.name}">${item.name}</div>
            <div class="item-meta">${formatBytes(item.size)} • ${timeStr} • ${dirIcon}</div>
        </div>
        <div class="item-actions">
            <button class="btn-sm" onclick="openFile('${encodeURIComponent(item.path)}')">打开</button>
        </div>
    `;

    if (prepend && historyList.firstChild) {
        historyList.insertBefore(div, historyList.firstChild);
    } else {
        historyList.appendChild(div);
    }

    txtHistoryCount.textContent = `共 ${fileList.length} 个文件`;
}

window.openFile = async function(encodedPath) {
    const path = decodeURIComponent(encodedPath);
    try {
        await fetch('/api/open_file', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: path })
        });
    } catch (e) {
        alert("打开文件失败: " + e);
    }
};

// Global Drag & Drop Events on WINDOW
let dragDepth = 0;

['dragenter', 'dragover'].forEach(name => {
    window.addEventListener(name, (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (e.dataTransfer) {
            e.dataTransfer.dropEffect = 'copy';
        }
        if (name === 'dragenter') {
            dragDepth++;
            const isModalOpen = phoneStorageModal && phoneStorageModal.style.display !== 'none';
            if (isModalOpen) {
                const folderName = currentDirectoryPath.split('/').filter(Boolean).pop() || "内部存储";
                dragOverlayTitle.textContent = "释放文件或文件夹，存入手机当前目录";
                dragOverlayTarget.textContent = `📂 目标目录: ${folderName} (${currentDirectoryPath})`;
                dragOverlayTarget.style.display = 'inline-block';
            } else {
                dragOverlayTitle.textContent = "释放文件或文件夹，立即传输到手机";
                dragOverlayTarget.style.display = 'none';
            }
            if (globalDragOverlay) globalDragOverlay.style.display = 'flex';
        }
    }, false);
});

window.addEventListener('dragleave', (e) => {
    e.preventDefault();
    e.stopPropagation();
    dragDepth--;
    if (dragDepth <= 0) {
        dragDepth = 0;
        if (globalDragOverlay) globalDragOverlay.style.display = 'none';
    }
}, false);

window.addEventListener('drop', async (e) => {
    e.preventDefault();
    e.stopPropagation();
    dragDepth = 0;
    if (globalDragOverlay) globalDragOverlay.style.display = 'none';

    if (!isDeviceConnected) {
        alert("⚠️ 尚未连接手机！请先使用手机端【跨屏快传】扫描屏幕二维码建立互联。");
        return;
    }

    const isModalOpen = phoneStorageModal && phoneStorageModal.style.display !== 'none';
    const targetDir = isModalOpen ? currentDirectoryPath : "";

    handleDroppedItems(e.dataTransfer, targetDir);
}, false);

// Dropzone specific click
dropzone.addEventListener('click', (e) => {
    // If clicked the folder button, let its handler handle it
    if (e.target.id === 'btnBrowseFolder') return;
    filePicker.click();
});

// Event Listeners
selectIp.addEventListener('change', (e) => {
    loadServerInfo(e.target.value);
});

btnCopyUrl.addEventListener('click', () => {
    navigator.clipboard.writeText(txtCurrentUrl.textContent);
    btnCopyUrl.textContent = "已复制";
    setTimeout(() => btnCopyUrl.textContent = "复制", 1500);
});

btnEnterStorage.addEventListener('click', openPhoneStorageModal);
btnDisconnect.addEventListener('click', disconnectDevice);

btnCloseModal.addEventListener('click', closePhoneStorageModal);
btnDismissModal.addEventListener('click', closePhoneStorageModal);
btnRefreshPhoneStorage.addEventListener('click', () => requestPhoneDirectory(currentDirectoryPath));
btnModalDisconnect.addEventListener('click', disconnectDevice);

btnGoRoot.addEventListener('click', () => {
    requestPhoneDirectory(currentRootPath || "/storage/emulated/0");
});

btnGoParent.addEventListener('click', () => {
    if (currentParentPath) {
        requestPhoneDirectory(currentParentPath);
    }
});

btnModalUploadToPhone.addEventListener('click', () => {
    modalFolderFileInput.click();
});

btnModalUploadFolderToPhone.addEventListener('click', async () => {
    if (window.pywebview && window.pywebview.api && window.pywebview.api.select_folder) {
        try {
            const folderPath = await window.pywebview.api.select_folder();
            if (folderPath) {
                progressCard.style.display = 'block';
                txtProgressTitle.textContent = `正在打包并发送本地文件夹...`;
                const res = await fetch('/api/send_local_folder', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: folderPath, target_dir: currentDirectoryPath, as_zip: true })
                });
                progressCard.style.display = 'none';
                const data = await res.json();
                if (data.status === 'ok') {
                    showNotification(`✅ 文件夹压缩包已成功发送给手机！`);
                    setTimeout(() => requestPhoneDirectory(currentDirectoryPath), 1000);
                } else {
                    alert("发送失败: " + data.detail);
                }
                return;
            }
        } catch (_) {}
    }
    modalFolderDirInput.click();
});

modalFolderFileInput.addEventListener('change', (e) => {
    if (e.target.files && e.target.files.length > 0) {
        sendBatchFiles(Array.from(e.target.files), currentDirectoryPath);
    }
});

modalFolderDirInput.addEventListener('change', (e) => {
    if (e.target.files && e.target.files.length > 0) {
        handleDirectoryInput(e.target.files, currentDirectoryPath);
    }
});

if (btnTriggerRemotePicker) {
    btnTriggerRemotePicker.addEventListener('click', triggerPhoneFilePicker);
}

if (btnRequestPermission) {
    btnRequestPermission.addEventListener('click', async () => {
        try {
            await fetch('/api/request_storage_permission', { method: 'POST' });
            showNotification("已向手机发出权限请求，请在手机屏幕上允许访问！");
        } catch (e) {
            alert("请求权限失败: " + e);
        }
    });
}

document.querySelectorAll('.shortcut-chip').forEach(chip => {
    chip.addEventListener('click', (e) => {
        const folder = e.currentTarget.getAttribute('data-folder');
        if (folder) {
            const root = currentRootPath || "/storage/emulated/0";
            requestPhoneDirectory(`${root}/${folder}`);
        }
    });
});

btnBrowseFiles.addEventListener('click', (e) => {
    e.stopPropagation();
    filePicker.click();
});

filePicker.addEventListener('change', (e) => {
    if (e.target.files && e.target.files.length > 0) {
        sendBatchFiles(Array.from(e.target.files));
    }
});

btnBrowseFolder.addEventListener('click', async (e) => {
    e.stopPropagation();
    if (window.pywebview && window.pywebview.api && window.pywebview.api.select_folder) {
        try {
            const folderPath = await window.pywebview.api.select_folder();
            if (folderPath) {
                progressCard.style.display = 'block';
                txtProgressTitle.textContent = `正在打包并发送文件夹...`;
                const res = await fetch('/api/send_local_folder', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: folderPath, as_zip: true })
                });
                progressCard.style.display = 'none';
                const data = await res.json();
                if (data.status === 'ok') {
                    showNotification(`✅ 文件夹压缩包已发送给手机！`);
                } else {
                    alert("发送失败: " + data.detail);
                }
                return;
            }
        } catch (_) {}
    }
    folderPicker.click();
});

folderPicker.addEventListener('change', (e) => {
    if (e.target.files && e.target.files.length > 0) {
        handleDirectoryInput(e.target.files);
    }
});

btnOpenFolder.addEventListener('click', async () => {
    try {
        await fetch('/api/open_folder', { method: 'POST' });
    } catch (e) {
        alert("无法打开文件夹: " + e);
    }
});

// Modal Paste & New Folder Button Listeners
if (btnModalPaste) {
    btnModalPaste.addEventListener('click', () => {
        executePasteAction(currentDirectoryPath);
    });
}

if (btnModalNewFolder) {
    btnModalNewFolder.addEventListener('click', () => {
        createNewFolderPrompt();
    });
}

// Background right click on phoneFileList
phoneFileList.addEventListener('contextmenu', (e) => {
    if (!e.target.closest('.phone-explorer-row')) {
        e.preventDefault();
        clearSelection();
        showContextMenu(e.clientX, e.clientY, null);
    }
});

phoneFileList.addEventListener('click', (e) => {
    if (!e.target.closest('.phone-explorer-row')) {
        clearSelection();
        hideContextMenu();
    }
});

// Context Menu Event Handlers
if (cmenuOpen) {
    cmenuOpen.addEventListener('click', () => {
        hideContextMenu();
        if (selectedPhoneItem) {
            if (selectedPhoneItem.is_dir) {
                enterPhoneFolder(encodeURIComponent(selectedPhoneItem.path));
            } else {
                pullFileFromPhone(encodeURIComponent(selectedPhoneItem.path));
            }
        }
    });
}

if (cmenuDownload) {
    cmenuDownload.addEventListener('click', () => {
        hideContextMenu();
        if (selectedPhoneItem && !selectedPhoneItem.is_dir) {
            pullFileFromPhone(encodeURIComponent(selectedPhoneItem.path));
        }
    });
}

if (cmenuCopy) {
    cmenuCopy.addEventListener('click', () => {
        hideContextMenu();
        if (!selectedPhoneItem) return;
        phoneClipboard = {
            action: 'copy',
            path: selectedPhoneItem.path,
            name: selectedPhoneItem.name,
            is_dir: selectedPhoneItem.is_dir
        };
        showNotification(`📋 已复制手机项: ${selectedPhoneItem.name}`);
    });
}

if (cmenuCut) {
    cmenuCut.addEventListener('click', () => {
        hideContextMenu();
        if (!selectedPhoneItem) return;
        phoneClipboard = {
            action: 'move',
            path: selectedPhoneItem.path,
            name: selectedPhoneItem.name,
            is_dir: selectedPhoneItem.is_dir
        };
        document.querySelectorAll('.phone-explorer-row').forEach(r => r.classList.remove('is-cut'));
        const curRow = document.querySelector(`.phone-explorer-row[data-path="${CSS.escape(selectedPhoneItem.path)}"]`);
        if (curRow) curRow.classList.add('is-cut');
        showNotification(`✂️ 已剪切手机项: ${selectedPhoneItem.name}`);
    });
}

if (cmenuPaste) {
    cmenuPaste.addEventListener('click', () => {
        hideContextMenu();
        executePasteAction(currentDirectoryPath);
    });
}

if (cmenuRename) {
    cmenuRename.addEventListener('click', () => {
        hideContextMenu();
        if (!selectedPhoneItem) return;
        renamePhoneFileRemote(selectedPhoneItem.path, selectedPhoneItem.name);
    });
}

if (cmenuNewFolder) {
    cmenuNewFolder.addEventListener('click', () => {
        hideContextMenu();
        createNewFolderPrompt();
    });
}

if (cmenuDelete) {
    cmenuDelete.addEventListener('click', () => {
        hideContextMenu();
        if (!selectedPhoneItem) return;
        deletePhoneFileRemote(selectedPhoneItem.path, selectedPhoneItem.is_dir);
    });
}

// Close context menu on outside click
document.addEventListener('click', (e) => {
    if (explorerContextMenu && !explorerContextMenu.contains(e.target)) {
        hideContextMenu();
    }
});

// Keyboard shortcuts for Phone Explorer
window.addEventListener('keydown', (e) => {
    const isModalOpen = phoneStorageModal && phoneStorageModal.style.display !== 'none';
    if (!isModalOpen) return;

    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

    if (e.ctrlKey && (e.key === 'c' || e.key === 'C')) {
        if (selectedPhoneItem) {
            e.preventDefault();
            phoneClipboard = {
                action: 'copy',
                path: selectedPhoneItem.path,
                name: selectedPhoneItem.name,
                is_dir: selectedPhoneItem.is_dir
            };
            showNotification(`📋 已复制手机项: ${selectedPhoneItem.name}`);
        }
    } else if (e.ctrlKey && (e.key === 'x' || e.key === 'X')) {
        if (selectedPhoneItem) {
            e.preventDefault();
            phoneClipboard = {
                action: 'move',
                path: selectedPhoneItem.path,
                name: selectedPhoneItem.name,
                is_dir: selectedPhoneItem.is_dir
            };
            document.querySelectorAll('.phone-explorer-row').forEach(r => r.classList.remove('is-cut'));
            const curRow = document.querySelector(`.phone-explorer-row[data-path="${CSS.escape(selectedPhoneItem.path)}"]`);
            if (curRow) curRow.classList.add('is-cut');
            showNotification(`✂️ 已剪切手机项: ${selectedPhoneItem.name}`);
        }
    } else if (e.ctrlKey && (e.key === 'v' || e.key === 'V')) {
        e.preventDefault();
        executePasteAction(currentDirectoryPath);
    } else if (e.key === 'Delete') {
        if (selectedPhoneItem) {
            e.preventDefault();
            deletePhoneFileRemote(selectedPhoneItem.path, selectedPhoneItem.is_dir);
        }
    } else if (e.key === 'F2') {
        if (selectedPhoneItem) {
            e.preventDefault();
            renamePhoneFileRemote(selectedPhoneItem.path, selectedPhoneItem.name);
        }
    } else if (e.key === 'Escape') {
        hideContextMenu();
    }
});

// Init
window.addEventListener('DOMContentLoaded', () => {
    loadServerInfo();
    initWebSocket();
});
