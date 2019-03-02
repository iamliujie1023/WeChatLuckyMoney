package xyz.monkeytong.hongbao.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.widget.Toast;


import java.io.ByteArrayOutputStream;
import java.io.IOException;

import xyz.monkeytong.hongbao.R;

/**
 * Created by Zhongyi on 1/20/16.
 * Util for app update task.
 */
public class UpdateTask extends AsyncTask<String, String, String> {
    public static int count = 0;
    private Context context;
    private boolean isUpdateOnRelease;
    public static final String updateUrl = "https://api.github.com/repos/geeeeeeeeek/WeChatLuckyMoney/releases/latest";

    public UpdateTask(Context context, boolean needUpdate) {
        this.context = context;
        this.isUpdateOnRelease = needUpdate;
        if (this.isUpdateOnRelease) Toast.makeText(context, context.getString(R.string.checking_new_version), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected String doInBackground(String... uri) {
        return null;
    }

    @Override
    protected void onPostExecute(String result) {
        super.onPostExecute(result);
    }

    public void update() {
        super.execute(updateUrl);
    }
}