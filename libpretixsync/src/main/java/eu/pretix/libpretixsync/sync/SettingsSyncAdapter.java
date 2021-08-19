package eu.pretix.libpretixsync.sync;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import eu.pretix.libpretixsync.api.PretixApi;
import eu.pretix.libpretixsync.db.Settings;
import io.requery.BlockingEntityStore;
import io.requery.Persistable;

public class SettingsSyncAdapter extends BaseSingleObjectSyncAdapter<Settings> {
    public SettingsSyncAdapter(BlockingEntityStore<Persistable> store, String eventSlug, String key, PretixApi api, SyncManager.ProgressFeedback feedback) {
        super(store, eventSlug, key, api, feedback);
    }

    @Override
    Settings getKnownObject() {
        List<Settings> is = store.select(Settings.class)
                .where(Settings.SLUG.eq(eventSlug))
                .get().toList();
        if (is.size() == 0) {
            return null;
        } else if (is.size() == 1) {
            return is.get(0);
        } else {
            // What's going on here? Let's delete and re-fetch
            store.delete(is);
            return null;
        }
    }

    @Override
    public void updateObject(Settings obj, JSONObject jsonobj) throws JSONException {
        obj.setSlug(eventSlug);
        obj.setName(jsonobj.optString("invoice_address_from_name"));
        obj.setAddress(jsonobj.optString("invoice_address_from"));
        obj.setZipcode(jsonobj.optString("invoice_address_from_zipcode"));
        obj.setCity(jsonobj.optString("invoice_address_from_city"));
        obj.setCountry(jsonobj.optString("invoice_address_from_country"));
        obj.setTax_id(jsonobj.optString("invoice_address_from_tax_id"));
        obj.setVat_id(jsonobj.optString("invoice_address_from_vat_id"));
        obj.setCovid_certificates_allow_vaccinated(jsonobj.optBoolean("covid_certificates_allow_vaccinated"));
        obj.setCovid_certificates_allow_vaccinated_min(jsonobj.optInt("covid_certificates_allow_vaccinated_min"));
        obj.setCovid_certificates_allow_vaccinated_max(jsonobj.optInt("covid_certificates_allow_vaccinated_max"));
        obj.setCovid_certificates_record_proof_vaccinated(jsonobj.optBoolean("covid_certificates_record_proof_vaccinated"));
        obj.setCovid_certificates_allow_cured(jsonobj.optBoolean("covid_certificates_allow_cured"));
        obj.setCovid_certificates_allow_cured_min(jsonobj.optInt("covid_certificates_allow_cured_min"));
        obj.setCovid_certificates_allow_cured_max(jsonobj.optInt("covid_certificates_allow_cured_max"));
        obj.setCovid_certificates_record_proof_cured(jsonobj.optBoolean("covid_certificates_record_proof_cured"));
        obj.setCovid_certificates_allow_tested_pcr(jsonobj.optBoolean("covid_certificates_allow_tested_pcr"));
        obj.setCovid_certificates_allow_tested_pcr_min(jsonobj.optInt("covid_certificates_allow_tested_pcr_min"));
        obj.setCovid_certificates_allow_tested_pcr_max(jsonobj.optInt("covid_certificates_allow_tested_pcr_max"));
        obj.setCovid_certificates_record_proof_tested_pcr(jsonobj.optBoolean("covid_certificates_record_proof_tested_pcr"));
        obj.setCovid_certificates_allow_tested_antigen_unknown(jsonobj.optBoolean("covid_certificates_allow_tested_antigen_unknown"));
        obj.setCovid_certificates_allow_tested_antigen_unknown_min(jsonobj.optInt("covid_certificates_allow_tested_antigen_unknown_min"));
        obj.setCovid_certificates_allow_tested_antigen_unknown_max(jsonobj.optInt("covid_certificates_allow_tested_antigen_unknown_max"));
        obj.setCovid_certificates_record_proof_tested_antigen_unknown(jsonobj.optBoolean("covid_certificates_record_proof_tested_antigen_unknown"));
        obj.setCovid_certificates_accept_eudgc(jsonobj.optBoolean("covid_certificates_accept_eudgc"));
        obj.setCovid_certificates_accept_manual(jsonobj.optBoolean("covid_certificates_accept_manual"));
        obj.setPretixpos_additional_receipt_text(jsonobj.optString("pretixpos_additional_receipt_text"));
        obj.setJson_data(jsonobj.toString());
    }

    @Override
    protected String getUrl() {
        return api.eventResourceUrl("settings");
    }

    @Override
    String getResourceName() {
        return "settings";
    }

    @Override
    Settings newEmptyObject() {
        return new Settings();
    }
}
