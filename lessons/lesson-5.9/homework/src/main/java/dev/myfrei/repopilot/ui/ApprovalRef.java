package dev.myfrei.repopilot.ui;

import java.util.UUID;

public record ApprovalRef(String kind, UUID id, String status, String summary) {}
