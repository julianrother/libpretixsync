package eu.pretix.libpretixsync.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import eu.pretix.libpretixsync.api.ApiException;
import eu.pretix.libpretixsync.api.PretixApi;
import eu.pretix.libpretixsync.api.ResourceNotModified;
import eu.pretix.libpretixsync.db.RemoteObject;
import eu.pretix.libpretixsync.utils.JSONUtils;
import io.requery.BlockingEntityStore;
import io.requery.Persistable;
import io.requery.query.Tuple;

public abstract class BaseDownloadSyncAdapter<T extends RemoteObject & Persistable, K> implements DownloadSyncAdapter, BatchedQueryIterator.BatchedQueryCall<K, T> {

    protected BlockingEntityStore<Persistable> store;
    protected PretixApi api;
    protected String eventSlug;
    protected FileStorage fileStorage;
    protected Set<K> knownIDs;
    protected Set<K> seenIDs;

    public BaseDownloadSyncAdapter(BlockingEntityStore<Persistable> store, FileStorage fileStorage, String eventSlug, PretixApi api) {
        this.store = store;
        this.api = api;
        this.eventSlug = eventSlug;
        this.fileStorage = fileStorage;
    }

    @Override
    public void download() throws JSONException, ApiException {
        try {
            knownIDs = getKnownIDs();
            seenIDs = new HashSet<>();
            downloadData();

            if (deleteUnseen()) {
                for (Map.Entry<K, T> obj : getKnownObjects(knownIDs).entrySet()) {
                    prepareDelete(obj.getValue());
                    store.delete(obj.getValue());
                }
            }
        } catch (ResourceNotModified e) {
            // Do nothing
        }
    }

    protected Iterator<T> getKnownObjectsIterator(Set<K> ids) {
        return new BatchedQueryIterator<K, T>(ids.iterator(), this);
    }

    abstract Iterator<Tuple> getKnownIDsIterator();

    protected Set<K> getKnownIDs() {
        Iterator<Tuple> it = getKnownIDsIterator();
        Set<K> known = new HashSet<>();
        while (it.hasNext()) {
            Tuple obj = it.next();
            known.add(obj.get(0));
        }
        return known;
    }

    protected Map<K, T> getKnownObjects(Set<K> ids) {
        Iterator<T> it = getKnownObjectsIterator(ids);
        Map<K, T> known = new HashMap<>();
        while (it.hasNext()) {
            try {
                T obj = it.next();
                known.put(getId(obj), obj);
            } catch (BatchEmptyException e) {
                // Ignore
            }
        }
        return known;
    }

    protected void processPage(final JSONArray data) {
        int l = data.length();
        store.runInTransaction(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                Set<K> fetchIds = new HashSet<>();
                for (int i = 0; i < l; i++) {
                    JSONObject jsonobj = data.getJSONObject(i);
                    fetchIds.add(getId(jsonobj));
                }

                Map<K, T> known = getKnownObjects(fetchIds);
                List<T> inserts = new ArrayList<>();

                for (int i = 0; i < l; i++) {
                    JSONObject jsonobj = data.getJSONObject(i);
                    K jsonid = getId(jsonobj);
                    T obj;
                    JSONObject old = null;
                    if (seenIDs.contains(jsonid)) {
                        continue;
                    } else if (known.containsKey(jsonid)) {
                        obj = known.get(jsonid);
                        old = obj.getJSON();
                    } else {
                        obj = newEmptyObject();
                    }
                    if (known.containsKey(jsonid)) {
                        known.remove(jsonid);
                        knownIDs.remove(jsonid);
                        if (!JSONUtils.similar(jsonobj, old)) {
                            updateObject(obj, jsonobj);
                            store.update(obj);
                        }
                    } else {
                        updateObject(obj, jsonobj);
                        if (autoPersist()) {
                            inserts.add(obj);
                        }
                    }
                    seenIDs.add(jsonid);
                }
                store.insert(inserts);
                return null;
            }
        });
    }

    protected void prepareDelete(T obj) {

    }

    protected boolean autoPersist() {
        return true;
    }

    protected boolean deleteUnseen() {
        return true;
    }

    public abstract void updateObject(T obj, JSONObject jsonobj) throws JSONException;

    protected void downloadData() throws JSONException, ApiException, ResourceNotModified {
        String url = api.eventResourceUrl(getResourceName());
        boolean isFirstPage = true;
        while (true) {
            JSONObject page = downloadPage(url, isFirstPage);
            processPage(page.getJSONArray("results"));
            if (page.isNull("next")) {
                break;
            }
            url = page.getString("next");
            isFirstPage = false;
        }
    }

    protected JSONObject downloadPage(String url, boolean isFirstPage) throws ApiException, ResourceNotModified {
        return api.fetchResource(url).getData();
    }

    abstract String getResourceName();

    abstract K getId(JSONObject obj) throws JSONException;

    abstract K getId(T obj);

    abstract T newEmptyObject();
}
