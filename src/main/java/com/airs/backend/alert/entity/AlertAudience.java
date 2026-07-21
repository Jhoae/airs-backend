package com.airs.backend.alert.entity;

public enum AlertAudience {
    // 관리자 화면에만 보여주는 운영 알림 대상이다.
    ADMIN,
    // 일반 사용자 화면에만 보여주는 알림 대상이다.
    USER,
    // 관리자와 일반 사용자 모두에게 보여주는 알림 대상이다.
    ALL
}
