package eu.pretix.libpretixsync.api;

import java.io.IOException;

public class DeviceAccessRevokedException extends ApiException {

    public DeviceAccessRevokedException(String msg) {
        super(msg);
    }

    public DeviceAccessRevokedException(String msg, Exception e) {
        super(msg, e);
    }

}
