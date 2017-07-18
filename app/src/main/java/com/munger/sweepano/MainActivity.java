package com.munger.sweepano;

import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity
{
    private static MainActivity instance;
    private Fragment currentFragment;

    public static MainActivity getInstance()
    {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        instance = this;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        openCaptureView();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        currentFragment.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    public void openCaptureView()
    {
        FragmentTransaction trans = getSupportFragmentManager().beginTransaction();
        CaptureFragment frag = new CaptureFragment();
        currentFragment = frag;

        trans.add(R.id.container, frag, "capture");
        trans.commit();
    }
}
