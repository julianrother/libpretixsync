package eu.pretix.libpretixsync.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import eu.pretix.libpretixsync.SentryInterface;
import eu.pretix.libpretixsync.api.ApiException;
import eu.pretix.libpretixsync.api.DeviceAccessRevokedException;
import eu.pretix.libpretixsync.api.PretixApi;
import eu.pretix.libpretixsync.api.ResourceNotModified;
import eu.pretix.libpretixsync.check.TicketCheckProvider;
import eu.pretix.libpretixsync.config.ConfigStore;
import eu.pretix.libpretixsync.db.CheckIn;
import eu.pretix.libpretixsync.db.Closing;
import eu.pretix.libpretixsync.db.Order;
import eu.pretix.libpretixsync.db.OrderPosition;
import eu.pretix.libpretixsync.db.Question;
import eu.pretix.libpretixsync.db.QueuedCheckIn;
import eu.pretix.libpretixsync.db.QueuedOrder;
import eu.pretix.libpretixsync.db.Receipt;
import eu.pretix.libpretixsync.db.ReceiptLine;
import eu.pretix.libpretixsync.db.ResourceLastModified;
import io.requery.BlockingEntityStore;
import io.requery.Persistable;

public class SyncManager {
    private SentryInterface sentry;
    private PretixApi api;
    private ConfigStore configStore;
    private long upload_interval;
    private long download_interval;
    private BlockingEntityStore<Persistable> dataStore;
    private FileStorage fileStorage;
    private boolean is_pretixpos;
    private CanceledState canceled;
    private int app_version;
    private String hardware_brand;
    private String hardware_model;
    private String software_brand;
    private String software_version;

    public class CanceledState {
        private boolean canceled = false;

        public boolean isCanceled() {
            return canceled;
        }

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }
    }

    public interface ProgressFeedback {
        public void postFeedback(String current_action);
    }

    public SyncManager(ConfigStore configStore, PretixApi api, SentryInterface sentry, BlockingEntityStore<Persistable> dataStore, FileStorage fileStorage, long upload_interval, long download_interval, boolean is_pretixpos, int app_version, String hardware_brand, String hardware_model, String software_brand, String software_version) {
        this.configStore = configStore;
        this.api = api;
        this.sentry = sentry;
        this.upload_interval = upload_interval;
        this.download_interval = download_interval;
        this.dataStore = dataStore;
        this.fileStorage = fileStorage;
        this.is_pretixpos = is_pretixpos;
        this.canceled = new CanceledState();
        this.app_version = app_version;
        this.hardware_brand = hardware_brand;
        this.hardware_model = hardware_model;
        this.software_brand = software_brand;
        this.software_version = software_version;
    }

    public SyncResult sync(boolean force) {
        return sync(force, null);
    }

    /**
     * Synchronize data with the pretix server
     *
     * @param force Force a new sync
     * @return A SyncResult describing the results of the synchronization
     */
    public SyncResult sync(boolean force, SyncManager.ProgressFeedback feedback) {
        if (!configStore.isConfigured()) {
            return new SyncResult(false, false);
        }

        if (!force && (System.currentTimeMillis() - configStore.getLastSync()) < upload_interval) {
            return new SyncResult(false, false);
        }
        if (!force && (System.currentTimeMillis() - configStore.getLastFailedSync()) < 30000) {
            return new SyncResult(false, false);
        }

        try {
            if (app_version != configStore.getDeviceKnownVersion()) {
                JSONObject apiBody = new JSONObject();
                apiBody.put("hardware_brand", hardware_brand);
                apiBody.put("hardware_model", hardware_model);
                apiBody.put("software_brand", software_brand);
                apiBody.put("software_version", software_version);
                api.postResource(
                        api.apiURL("device/update"),
                        apiBody
                );
                configStore.setDeviceKnownVersion(app_version);
            }
        } catch (ApiException e) {
            configStore.setLastFailedSync(System.currentTimeMillis());
            configStore.setLastFailedSyncMsg(e.getMessage());
        } catch (JSONException e) {
            configStore.setLastFailedSync(System.currentTimeMillis());
            configStore.setLastFailedSyncMsg(e.getMessage());
        }

        canceled.setCanceled(false);
        boolean download = force || (System.currentTimeMillis() - configStore.getLastDownload()) > download_interval;
        try {
            if (feedback != null) {
                feedback.postFeedback("Uploading data…");
            }
            uploadOrders();
            if (canceled.isCanceled()) throw new SyncException("Canceled");
            uploadTicketData();
            if (canceled.isCanceled()) throw new SyncException("Canceled");
            uploadReceipts();
            if (canceled.isCanceled()) throw new SyncException("Canceled");
            uploadClosings();
            if (canceled.isCanceled()) throw new SyncException("Canceled");

            if (download) {
                if (feedback != null) {
                    feedback.postFeedback("Downloading data…");
                }
                downloadData(feedback);
                configStore.setLastDownload(System.currentTimeMillis());
            }

            if (feedback != null) {
                feedback.postFeedback("Finishing touches…");
            }
            configStore.setLastSync(System.currentTimeMillis());
            configStore.setLastFailedSync(0);
            if (feedback != null) {
                feedback.postFeedback("Sync completed.");
            }
        } catch (SyncException e) {
            configStore.setLastFailedSync(System.currentTimeMillis());
            configStore.setLastFailedSyncMsg(e.getMessage());
        }
        return new SyncResult(true, download);
    }

    private void download(DownloadSyncAdapter adapter) throws InterruptedException, ExecutionException, ApiException, JSONException {
        adapter.setCancelState(canceled);
        adapter.download();
    }

    public void cancel() {
        canceled.setCanceled(true);
    }

    protected void downloadData(SyncManager.ProgressFeedback feedback) throws SyncException {
        sentry.addBreadcrumb("sync.queue", "Start download");

        try {
            try {
                PretixApi.ApiResponse vresp = api.fetchResource(api.apiURL("version"));
                configStore.setKnownPretixVersion(vresp.getData().getLong("pretix_numeric"));
            } catch (ApiException | JSONException | ResourceNotModified e) {
                // ignore
                e.printStackTrace();
            }


            download(new EventSyncAdapter(dataStore, configStore.getEventSlug(), configStore.getEventSlug(), api, feedback));
            if (configStore.getSubEventId() != null) {
                download(new SubEventSyncAdapter(dataStore, configStore.getEventSlug(), String.valueOf(configStore.getSubEventId()), api, feedback));
            }
            download(new ItemCategorySyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
            download(new ItemSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
            download(new QuestionSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
            if (is_pretixpos) {
                // We don't need these on pretixSCAN, so we can save some traffic
                download(new QuotaSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
                download(new TaxRuleSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
                download(new TicketLayoutSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
            }
            if (!is_pretixpos) {
                // We don't need these on pretixPOS, so we can save some traffic
                download(new BadgeLayoutSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
                download(new CheckInListSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), configStore.getSubEventId(), api, feedback));

                try {
                    download(new BadgeLayoutItemSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
                } catch (ApiException e) {
                    if (e.getMessage().toLowerCase().contains("not found")) {
                        // ignore, this is only supported from pretix 2.5. We have legacy code in BadgeLayoutSyncAdapter to fall back to
                    } else {
                        throw e;
                    }
                }
                download(new OrderSyncAdapter(dataStore, fileStorage, configStore.getEventSlug(), api, feedback));
            }

            if (is_pretixpos) {
                // We don't need these on pretixSCAN, so we can save some traffic
                try {
                    download(new SettingsSyncAdapter(dataStore, configStore.getEventSlug(), configStore.getEventSlug(), api, feedback));
                } catch (ApiException e) {
                    // Older pretix installations
                    download(new InvoiceSettingsSyncAdapter(dataStore, configStore.getEventSlug(), configStore.getEventSlug(), api, feedback));
                }
            }

        } catch (DeviceAccessRevokedException e) {
            int deleted = 0;
            deleted += dataStore.delete(CheckIn.class).get().value();
            deleted += dataStore.delete(OrderPosition.class).get().value();
            deleted += dataStore.delete(Order.class).get().value();
            deleted += dataStore.delete(ResourceLastModified.class).get().value();
            throw new SyncException(e.getMessage());
        } catch (JSONException e) {
            e.printStackTrace();
            throw new SyncException("Unknown server response");
        } catch (ApiException e) {
            sentry.addBreadcrumb("sync.tickets", "API Error: " + e.getMessage());
            throw new SyncException(e.getMessage());
        } catch (ExecutionException e) {
            sentry.captureException(e);
            throw new SyncException(e.getMessage());
        } catch (InterruptedException e) {
            sentry.captureException(e);
            throw new SyncException(e.getMessage());
        }
    }

    protected void uploadReceipts() throws SyncException {
        sentry.addBreadcrumb("sync.queue", "Start receipt upload");

        List<Receipt> receipts = dataStore.select(Receipt.class)
                .where(Receipt.OPEN.eq(false))
                .and(Receipt.SERVER_ID.isNull())
                .get().toList();

        try {
            for (Receipt receipt : receipts) {
                JSONObject data = receipt.toJSON();
                JSONArray lines = new JSONArray();
                for (ReceiptLine line : receipt.getLines()) {
                    lines.put(line.toJSON());
                }
                data.put("lines", lines);
                PretixApi.ApiResponse response = api.postResource(
                        api.organizerResourceUrl("posdevices/" + configStore.getPosId() + "/receipts"),
                        data
                );
                if (response.getResponse().code() == 201) {
                    receipt.setServer_id(response.getData().getLong("receipt_id"));
                    dataStore.update(receipt);
                } else {
                    throw new SyncException(response.getData().toString());
                }
            }
        } catch (JSONException e) {
            sentry.captureException(e);
            throw new SyncException("Unknown server response");
        } catch (ApiException e) {
            sentry.addBreadcrumb("sync.queue", "API Error: " + e.getMessage());
            throw new SyncException(e.getMessage());
        }

        sentry.addBreadcrumb("sync.queue", "Receipt upload complete");
    }

    protected void uploadOrders() throws SyncException {
        sentry.addBreadcrumb("sync.queue", "Start order upload");

        List<QueuedOrder> orders = dataStore.select(QueuedOrder.class)
                .where(QueuedOrder.ERROR.isNull())
                .get().toList();

        try {
            for (QueuedOrder qo : orders) {
                dataStore.runInTransaction(() -> {
                    qo.setLocked(true);
                    dataStore.update(qo, QueuedOrder.LOCKED);
                    return null;
                });
                try {
                    api.setEventSlug(qo.getEvent_slug());
                    PretixApi.ApiResponse resp = api.postResource(
                            api.eventResourceUrl("orders") + "?pdf_data=true&force=true",
                            new JSONObject(qo.getPayload()),
                            qo.getIdempotency_key()
                    );
                    if (resp.getResponse().code() == 201) {
                        Receipt r = qo.getReceipt();
                        r.setOrder_code(resp.getData().getString("code"));
                        dataStore.runInTransaction(() -> {
                            dataStore.update(r, Receipt.ORDER_CODE);
                            dataStore.delete(qo);
                            (new OrderSyncAdapter(dataStore, null, configStore.getEventSlug(), api, null)).standaloneRefreshFromJSON(resp.getData());
                            return null;
                        });
                    } else if (resp.getResponse().code() == 400) {
                        // TODO: User feedback or log in some way?
                        qo.setError(resp.getData().toString());
                        dataStore.update(qo);
                    }
                } finally {
                    api.setEventSlug(configStore.getEventSlug());
                }
            }
        } catch (JSONException e) {
            sentry.captureException(e);
            throw new SyncException("Unknown server response");
        } catch (ApiException e) {
            sentry.addBreadcrumb("sync.queue", "API Error: " + e.getMessage());
            throw new SyncException(e.getMessage());
        }

        sentry.addBreadcrumb("sync.queue", "Receipt upload complete");
    }

    protected void uploadClosings() throws SyncException {
        sentry.addBreadcrumb("sync.queue", "Start closings upload");

        List<Closing> closings = dataStore.select(Closing.class)
                .where(Closing.OPEN.eq(false))
                .and(Closing.SERVER_ID.isNull())
                .get().toList();

        try {
            for (Closing closing : closings) {
                PretixApi.ApiResponse response = api.postResource(
                        api.organizerResourceUrl("posdevices/" + configStore.getPosId() + "/closings"),
                        closing.toJSON()
                );
                if (response.getResponse().code() == 201) {
                    closing.setServer_id(response.getData().getLong("closing_id"));
                    dataStore.update(closing);
                } else {
                    throw new SyncException(response.getData().toString());
                }
            }
        } catch (JSONException e) {
            sentry.captureException(e);
            throw new SyncException("Unknown server response");
        } catch (ApiException e) {
            sentry.addBreadcrumb("sync.queue", "API Error: " + e.getMessage());
            throw new SyncException(e.getMessage());
        }

        sentry.addBreadcrumb("sync.queue", "Closings upload complete");
    }

    protected void uploadTicketData() throws SyncException {
        sentry.addBreadcrumb("sync.queue", "Start check-in upload");

        List<QueuedCheckIn> queued = dataStore.select(QueuedCheckIn.class)
                .get().toList();

        try {
            for (QueuedCheckIn qci : queued) {
                List<TicketCheckProvider.Answer> answers = new ArrayList<>();
                try {
                    JSONArray ja = new JSONArray(qci.getAnswers());
                    for (int i = 0; i < ja.length(); i++) {
                        JSONObject jo = ja.getJSONObject(i);
                        Question q = new Question();
                        q.setServer_id(jo.getLong("question"));
                        answers.add(new TicketCheckProvider.Answer(q, jo.getString("answer")));
                    }
                } catch (JSONException e) {
                }

                PretixApi.ApiResponse ar;
                try {
                    api.setEventSlug(qci.getEvent_slug());
                    if (qci.getDatetime_string() == null || qci.getDatetime_string().equals("")) {
                        // Backwards compatibility
                        ar = api.redeem(qci.getSecret(), qci.getDatetime(), true, qci.getNonce(), answers, qci.checkinListId, false, false, qci.getType());
                    } else {
                        ar = api.redeem(qci.getSecret(), qci.getDatetime_string(), true, qci.getNonce(), answers, qci.checkinListId, false, false, qci.getType());
                    }
                } finally {
                    api.setEventSlug(configStore.getEventSlug());
                }
                JSONObject response = ar.getData();
                String status = response.getString("status");
                if ("ok".equals(status)) {
                    dataStore.delete(qci);
                } else {
                    String reason = response.optString("reason");
                    if ("already_redeemed".equals(reason)) {
                        // Well, we can't really do something about this.
                        dataStore.delete(qci);
                    } // Else: Retry later
                }
            }
        } catch (JSONException e) {
            sentry.captureException(e);
            throw new SyncException("Unknown server response");
        } catch (ApiException e) {
            sentry.addBreadcrumb("sync.tickets", "API Error: " + e.getMessage());
            throw new SyncException(e.getMessage());
        }
        sentry.addBreadcrumb("sync.queue", "Check-in upload complete");
    }

    public class SyncResult {
        private boolean dataUploaded;
        private boolean dataDownloaded;

        public SyncResult(boolean dataUploaded, boolean dataDownloaded) {
            this.dataUploaded = dataUploaded;
            this.dataDownloaded = dataDownloaded;
        }

        public boolean isDataUploaded() {
            return dataUploaded;
        }

        public boolean isDataDownloaded() {
            return dataDownloaded;
        }
    }
}
