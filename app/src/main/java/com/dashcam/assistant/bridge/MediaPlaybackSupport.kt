package com.dashcam.assistant.bridge

internal fun guessMediaContentType(path: String, serverContentType: String?): String {
    val ext = path.substringAfterLast('.', "").substringBefore('?').lowercase()
    val mapped = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "flv" -> "video/x-flv"
        "webm" -> "video/webm"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "bmp" -> "image/bmp"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }
    val serverType = serverContentType?.substringBefore(';')?.trim()?.lowercase()
    if (serverType?.startsWith("video/") == true || serverType?.startsWith("image/") == true) {
        return serverType
    }
    return mapped ?: serverContentType ?: "application/octet-stream"
}
