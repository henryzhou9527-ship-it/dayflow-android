package com.henry.dayflow;

final class ScreenshotRecord {
    final long id;
    final long capturedAtMs;
    final String filePath;
    final long fileSize;
    final String packageName;
    final String appLabel;
    final String windowTitle;
    final String visibleText;

    ScreenshotRecord(long id, long capturedAtMs, String filePath, long fileSize, String packageName, String appLabel, String windowTitle, String visibleText) {
        this.id = id;
        this.capturedAtMs = capturedAtMs;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.windowTitle = windowTitle;
        this.visibleText = visibleText;
    }
}
