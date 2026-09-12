package io.github.sahajm99.innkeeper.model;

/** Account roles, from least to most privileged; matches ck_user_account_role. */
public enum Role {
    GUEST,
    STAFF,
    MANAGER
}
