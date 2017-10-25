package com.cyandev.bouncytext;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import me.cyandev.widget.BouncyText;

public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_VALUE = "1000";

    private BouncyText mBouncyText;
    private int[] mConfigValues = new int[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mBouncyText = findViewById(R.id.bouncy_text);
        mBouncyText.setText(DEFAULT_VALUE);

        configureSeekBar(R.id.duration_seek, R.id.duration_text, 50, 1000, 450, 0);
        configureSeekBar(R.id.stagger_seek, R.id.stagger_text, 0, 500, 45, 1);

        findViewById(R.id.change_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Context context = MainActivity.this;
                final FrameLayout layout = new FrameLayout(context);
                final EditText edit = new EditText(context);
                final ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                final int margins = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        16,
                        getResources().getDisplayMetrics());
                lp.setMargins(margins, margins, margins, margins);
                edit.setInputType(InputType.TYPE_CLASS_NUMBER);
                edit.setHint(DEFAULT_VALUE);
                layout.addView(edit, lp);

                AlertDialog alert = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.set_text)
                        .setMessage(R.string.set_text_message)
                        .setView(layout)
                        .setPositiveButton(R.string.done, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String text = edit.getText().toString();
                                if (TextUtils.isEmpty(text)) {
                                    text = DEFAULT_VALUE;
                                }

                                mBouncyText.suppressAnimations(true);
                                mBouncyText.setText(text);
                                mBouncyText.suppressAnimations(false);
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        findViewById(R.id.increase_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustValue(1);
            }
        });

        findViewById(R.id.decrease_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustValue(-1);
            }
        });

        RadioGroup directionRadioGroup = findViewById(R.id.direction_radio_group);
        for (int i = 1; i < directionRadioGroup.getChildCount(); i++) {
            final int value = i == 1 ? BouncyText.DIRECTION_UPWARD : BouncyText.DIRECTION_DOWNWARD;
            RadioButton.OnClickListener listener = new RadioButton.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mConfigValues[2] = value;
                    applyConfig();
                }
            };

            RadioButton radio = (RadioButton) directionRadioGroup.getChildAt(i);
            radio.setOnClickListener(listener);
        }
    }

    private void adjustValue(int delta) {
        try {
            mBouncyText.setText("" + (Integer.parseInt(mBouncyText.getText().toString()) + delta));
        } catch (NumberFormatException ignored) {}
    }

    @SuppressLint("SetTextI18n")
    private void configureSeekBar(final int seekBarId, final int displayId, final int min,
                                  final int max, final int initial, final int cfgIndex) {
        final SeekBar sb = findViewById(seekBarId);
        final TextView tv = findViewById(displayId);
        sb.setMax(max - min);
        sb.setProgress(initial - min);
        tv.setText("" + initial);
        mConfigValues[cfgIndex] = initial;
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tv.setText("" + (min + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mConfigValues[cfgIndex] = min + seekBar.getProgress();
                applyConfig();
            }
        });
    }

    private void applyConfig() {
        mBouncyText.setAnimationDuration(mConfigValues[0]);
        mBouncyText.setAnimationStagger(mConfigValues[1]);
        mBouncyText.setAnimationDirection(mConfigValues[2]);
    }
}
