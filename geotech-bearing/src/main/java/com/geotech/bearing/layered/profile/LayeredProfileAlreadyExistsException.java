package com.geotech.bearing.layered.profile;

/**
 * 登记同名分层剖面时抛出（与单层档重名互不影响，只在分层剖面表内判重）。
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
