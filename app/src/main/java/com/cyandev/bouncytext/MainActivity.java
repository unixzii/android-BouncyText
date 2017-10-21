package com.cyandev.bouncytext;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

import me.cyandev.widget.BouncyText;

public class MainActivity extends AppCompatActivity {

    private int mCurrentNumber = 1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final BouncyText bouncyText = findViewById(R.id.bouncy_text);
        bouncyText.setText("" + mCurrentNumber);

        findViewById(R.id.change_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCurrentNumber -= 9;
                bouncyText.setText("" + mCurrentNumber);
            }
        });
    }
}
