package com.airs.backend.user;

public final class UserPolicy {

    public static final int EMAIL_MAX_LENGTH = 50;

    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 30;

    public static final int NICKNAME_MIN_LENGTH = 2;
    public static final int NICKNAME_MAX_LENGTH = 10;

    private UserPolicy() {
    }
}
