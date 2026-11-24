package org.xcore.plugin.model.enums;

public enum AuthResultStatus {
    SUCCESS,
    PASSWORD_CREATED,
    WRONG_PASSWORD,
    PASSWORD_TOO_SHORT,
    DISCORD_APPROVAL_REQUIRED,
    /** The account has staff grants but no password yet: it needs a code from {@code perm user <player> enroll}. */
    SETUP_REQUIRED,
    SESSION_NOT_FOUND,
    RATE_LIMITED,
    TOKEN_INVALID
}
