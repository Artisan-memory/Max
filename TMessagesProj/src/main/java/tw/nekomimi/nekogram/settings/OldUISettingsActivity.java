package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import xyz.nextalone.nagram.NaConfig;

// Max: groups all toggles that restore the pre-redesign (12.3.1) interface.
public class OldUISettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

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
        return "oldui";
    }

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell header = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.OldUISettingsHeader)));
    private final AbstractConfigCell classicNavigationRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getClassicNavigation(), getString(R.string.ClassicNavigationNotice)));
    private final AbstractConfigCell hideBottomNavigationBarRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getHideBottomNavigationBar()));
    private final AbstractConfigCell divider = cellGroup.appendCell(new ConfigCellDivider());

    @Override
    protected void updateRows() {
        addRowsToMap(cellGroup);
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NaConfig.INSTANCE.getClassicNavigation().getKey())) {
                // Root navigation is decided in LaunchActivity.onCreate: restart.
                AppRestartHelper.triggerRebirth(ApplicationLoader.applicationContext, new Intent(ApplicationLoader.applicationContext, LaunchActivity.class));
            } else if (key.equals(NaConfig.INSTANCE.getHideBottomNavigationBar().getKey())) {
                parentLayout.rebuildAllFragmentViews(false, false);
            }
        };

        return superView;
    }

    @Override
    public String getTitle() {
        return getString(R.string.OldUISettings);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

    }
}
