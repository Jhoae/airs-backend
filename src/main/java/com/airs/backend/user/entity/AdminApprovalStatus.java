package com.airs.backend.user.entity;

public enum AdminApprovalStatus {
    NOT_APPLICABLE,
    PENDING,
    APPROVED,
    REJECTED;

    public static AdminApprovalStatus from(CampusAdminStatus status) {
        return switch (status) {
            case PENDING -> PENDING;
            case APPROVED -> APPROVED;
            case REJECTED -> REJECTED;
        };
    }
}
