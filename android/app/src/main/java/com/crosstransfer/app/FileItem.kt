package com.crosstransfer.app

data class FileItem(
    val id: String,
    val name: String,
    val size: Long,
    val filePath: String,
    val timestamp: Long,
    val isIncoming: Boolean
) {
    fun getFormattedSize(): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val displaySize = size / Math.pow(1024.0, digitGroups.toDouble())
        return String.format("%.1f %s", displaySize, units[digitGroups])
    }

    fun getIcon(): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz") -> "📦"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") -> "🖼️"
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".avi") -> "🎬"
            lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".aac") -> "🎵"
            lower.endsWith(".pdf") -> "📕"
            lower.endsWith(".doc") || lower.endsWith(".docx") -> "📘"
            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "📊"
            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "📙"
            lower.endsWith(".apk") -> "🤖"
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json") -> "📝"
            else -> "📄"
        }
    }
}
