package com.geotech.bearing.layered.profile;

/**
 * 点名一个未登记分层剖面时抛出，明确报错而不是拿默认值凑结果。
 */
public class LayeredProfileNotFoundException extends RuntimeException {

    private final String profileName;

    public LayeredProfileNotFoundException(String profileName) {
        super("未登记的分层剖面：'" + profileName + "'。");
        this.profileName = profileName;
    }

    public String getProfileName() {
        return profileName;
    }
}
