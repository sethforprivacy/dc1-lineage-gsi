package com.dc1.amber;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Minimal rootless slider for the amber frontlight.
 * Shows the authoritative 0..1023 scale; "Full (255)" is the Daylight UI's
 * 255 (= full). The mirrored write happens via the setting observer; we also
 * write immediately for a snappy feel.
 */
public final class AmberSettingsActivity extends Activity {

    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setTextSize(20);
        title.setText(R.string.slider_title);
        root.addView(title);

        SeekBar slider = new SeekBar(this);
        slider.setMax(AmberService.SETTING_MAX);
        slider.setProgress(current());
        root.addView(slider);

        mStatus = new TextView(this);
        mStatus.setTextSize(13);
        root.addView(mStatus);

        LinearLayout buttons = new LinearLayout(this);
        Button off = new Button(this);
        off.setText(R.string.slider_off);
        buttons.addView(off);
        Button full = new Button(this);
        full.setText(R.string.slider_full);
        buttons.addView(full);
        root.addView(buttons);

        setContentView(root);

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    set(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        off.setOnClickListener(v -> {
            set(0);
            slider.setProgress(0);
        });
        full.setOnClickListener(v -> {
            set(AmberService.SETTING_MAX);
            slider.setProgress(AmberService.SETTING_MAX);
        });

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private int current() {
        return Settings.System.getInt(
                getContentResolver(), AmberService.SETTING, 0);
    }

    private void set(int value) {
        if (value < 0) {
            value = 0;
        }
        if (value > AmberService.SETTING_MAX) {
            value = AmberService.SETTING_MAX;
        }
        Settings.System.putInt(getContentResolver(), AmberService.SETTING, value);
        AmberService.mirrorSetting(this);
        refreshStatus();
    }

    private void refreshStatus() {
        String node = SystemProperties.get("ro.dc1.amber.node");
        if (node == null || node.isEmpty()) {
            AmberService.AmberLed led = AmberService.AmberLed.forContext(this);
            node = led.resolvedNode();
            int max = led.resolvedMax();
            if (node != null) {
                mStatus.setText(getString(R.string.status_node, node, max));
                return;
            }
        }
        if (node != null && !node.isEmpty()) {
            mStatus.setText(getString(R.string.status_node, node, -1));
        } else {
            mStatus.setText(R.string.status_no_node);
        }
    }
}
