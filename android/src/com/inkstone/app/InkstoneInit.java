package com.inkstone.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * 进程启动即执行：把从 TXT 解析出来的地址写进 MainActivity 的静态字段，
 * 保证 WebView 首次 loadUrl 时用的就是新地址。
 */
public class InkstoneInit extends ContentProvider {

    @Override
    public boolean onCreate() {
        try {
            Context context = getContext();
            if (context != null) {
                Endpoint.init(context.getApplicationContext());
                Endpoint.warmUp();
                Endpoint.refreshAsync();
                SettingsLauncher.register(context.getApplicationContext());
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
