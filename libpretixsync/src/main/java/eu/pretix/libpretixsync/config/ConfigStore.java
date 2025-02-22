package eu.pretix.libpretixsync.config;

import org.json.JSONObject;

import java.util.List;
import java.util.Set;

public interface ConfigStore {

    public boolean isDebug();

    public boolean isConfigured();

    public int getApiVersion();

    public String getApiUrl();

    public String getDeviceKnownName();

    public void setDeviceKnownName(String value);

    public String getDeviceKnownGateName();

    public void setDeviceKnownGateName(String value);

    public int getDeviceKnownVersion();

    public void setDeviceKnownVersion(int value);

    public JSONObject getDeviceKnownInfo();

    public void setDeviceKnownInfo(JSONObject value);

    public String getApiKey();

    public String getOrganizerSlug();

    public String getSyncCycleId();

    public List<String> getSynchronizedEvents();

    public Long getSelectedSubeventForEvent(String event);

    public Long getSelectedCheckinListForEvent(String event);

    public long getLastDownload();

    public void setLastDownload(long val);

    public long getLastSync();

    public void setLastSync(long val);

    public long getLastCleanup();

    public void setLastCleanup(long val);

    public long getLastFailedSync();

    public void setLastFailedSync(long val);

    public String getLastFailedSyncMsg();

    public void setLastFailedSyncMsg(String val);

    public Long getPosId();

    public void setKnownPretixVersion(Long val);

    public Long getKnownPretixVersion();

    public Boolean getAutoSwitchRequested();

    public Set<String> getKnownLiveEventSlugs();

    public void setKnownLiveEventSlugs(Set<String> slugs);
}
