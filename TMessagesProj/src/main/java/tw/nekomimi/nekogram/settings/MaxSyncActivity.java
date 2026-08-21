package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.util.function.Supplier;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellText;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.sync.SyncApi;
import tw.nekomimi.nekogram.sync.SyncSecrets;
import xyz.nextalone.nagram.NaConfig;

public class MaxSyncActivity extends BaseNekoXSettingsActivity {

    private BaseListAdapter listAdapter;
    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerServer = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.MaxSyncServer)));
    private final AbstractConfigCell serverUrlRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getSyncServerUrl(), "https://sync.example.com", null));
    private final AbstractConfigCell accessPasswordRow = cellGroup.appendCell(new SecretCell("MaxSyncAccessPassword",
            () -> SyncSecrets.isSet(SyncSecrets.KEY_ACCESS_PASSWORD),
            () -> promptForSecret(R.string.MaxSyncAccessPassword, SyncSecrets.KEY_ACCESS_PASSWORD, null)));
    private final AbstractConfigCell proxyTokenRow = cellGroup.appendCell(new SecretCell("MaxSyncProxyToken",
            () -> SyncSecrets.isSet(SyncSecrets.KEY_PROXY_TOKEN),
            () -> promptForSecret(R.string.MaxSyncProxyToken, SyncSecrets.KEY_PROXY_TOKEN, getString(R.string.MaxSyncProxyTokenNotice))));
    private final AbstractConfigCell fingerprintRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getSyncCertFingerprint(), "sha256", null));
    private final AbstractConfigCell serverDivider = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerEncryption = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.MaxSyncEncryption)));
    private final AbstractConfigCell passphraseRow = cellGroup.appendCell(new SecretCell("MaxSyncPassphrase",
            () -> SyncSecrets.isSet(SyncSecrets.KEY_PASSPHRASE),
            () -> promptForSecret(R.string.MaxSyncPassphrase, SyncSecrets.KEY_PASSPHRASE, getString(R.string.MaxSyncPassphraseNotice))));
    private final AbstractConfigCell encryptionDivider = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerSync = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.MaxSync)));
    private final AbstractConfigCell enabledRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSyncEnabled(), getString(R.string.MaxSyncEnabledNotice)));
    private final AbstractConfigCell wifiOnlyRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSyncWifiOnly(), getString(R.string.MaxSyncWifiOnlyNotice)));
    private final AbstractConfigCell testRow = cellGroup.appendCell(new ConfigCellText("MaxSyncTestConnection", this::testConnection));
    private final AbstractConfigCell forgetRow = cellGroup.appendCell(new ConfigCellText("MaxSyncForget", this::confirmForget));
    private final AbstractConfigCell syncDivider = cellGroup.appendCell(new ConfigCellDivider());

    @Override
    public String getTitle() {
        return getString(R.string.MaxSync);
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return "maxsync";
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);
        listAdapter = new BaseListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();
        return superView;
    }

    private void promptForSecret(int titleRes, String secretKey, String notice) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        editText.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setBackgroundDrawable(null);
        editText.setPadding(0, 0, 0, 0);
        editText.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField),
                getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                getThemedColor(Theme.key_windowBackgroundWhiteRedText3));
        editText.setText(SyncSecrets.get(secretKey));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(titleRes));
        if (!TextUtils.isEmpty(notice)) {
            builder.setMessage(notice);
        }
        builder.setView(editText);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            SyncSecrets.set(secretKey, editText.getText().toString().trim());
            if (listAdapter != null) {
                listAdapter.notifyDataSetChanged();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.show().setOnShowListener(dialog -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
    }

    private void testConnection() {
        if (!SyncApi.isConfigured()) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.MaxSyncNotConfigured)).show();
            return;
        }
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            try {
                SyncApi.login();
            } catch (Exception e) {
                error = e.getMessage();
            }
            String message = error;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (message == null) {
                    BulletinFactory.of(this).createSuccessBulletin(getString(R.string.MaxSyncConnected)).show();
                } else {
                    BulletinFactory.of(this).createErrorBulletin(message).show();
                }
            });
        });
    }

    private void confirmForget() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.MaxSyncForget))
                .setMessage(getString(R.string.MaxSyncForgetNotice))
                .setPositiveButton(getString(R.string.Remove), (dialog, which) -> {
                    SyncSecrets.clearAll();
                    NaConfig.INSTANCE.getSyncEnabled().setConfigBool(false);
                    NaConfig.INSTANCE.getSyncServerUrl().setConfigString("");
                    NaConfig.INSTANCE.getSyncCertFingerprint().setConfigString("");
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    /** A row whose value tracks whether the secret behind it is present, without ever showing it. */
    private static class SecretCell extends ConfigCellText {
        private final Supplier<Boolean> isSet;

        SecretCell(String key, Supplier<Boolean> isSet, Runnable onClick) {
            super(key, onClick);
            this.isSet = isSet;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder) {
            TextSettingsCell cell = (TextSettingsCell) holder.itemView;
            String value = getString(isSet.get() ? R.string.MaxSyncSecretSet : R.string.MaxSyncSecretUnset);
            cell.setTextAndValue(getString(getKey()), value, cellGroup.needSetDivider(this));
        }
    }
}
