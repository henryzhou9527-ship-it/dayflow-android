package com.henry.dayflow;

final class LlmCallLog {
    long id;
    long createdAtMs;
    Long batchId;
    String provider;
    String model;
    String operation;
    String status;
    Long latencyMs;
    int screenshotCount;
    int cardCount;
    String errorMessage;
    String requestSummary;
    String responseSummary;
}
