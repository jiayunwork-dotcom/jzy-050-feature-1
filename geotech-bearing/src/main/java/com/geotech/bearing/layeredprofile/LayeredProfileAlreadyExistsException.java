package com.geotech.bearing.layeredprofile;

/**
 * 登记同名分层剖面时抛出。
 */
public class LayeredProfileAlreadyExistsException extends RuntimeException {

    private final String profileName;

    public LayeredProfileAlreadyExistsException(String profileName) {
        super("分层剖面名已存在：'" + profileName + "'。");
        this.profileName = profileName;
    }

    public String getProfileName() {
        return profileName;
    }
}
