/*
 * Copyright (c) 2016 Ha Duy Trung
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.hidroh.materialistic.data;

import android.accounts.Account;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import io.github.hidroh.materialistic.AppUtils;
import io.github.hidroh.materialistic.Application;
import io.github.hidroh.materialistic.ItemActivity;
import io.github.hidroh.materialistic.Preferences;
import io.github.hidroh.materialistic.R;
import io.github.hidroh.materialistic.annotation.Synthetic;
import retrofit2.Call;
import retrofit2.Callback;

/**
 * Simple sync adapter that triggers OkHttp requests so their responses become available in
 * cache for subsequent requests
 */
class ItemSyncAdapter extends AbstractThreadedSyncAdapter {

    static final String SYNC_PREFERENCES_FILE = "_syncpreferences";
    private static final String EXTRA_ID = ItemSyncAdapter.class.getName() + ".EXTRA_ID";
    private static final String NOTIFICATION_GROUP_KEY = "group";
    private static final String EXTRA_CONNECTION_ENABLED = ItemSyncAdapter.class.getName() +
            ".EXTRA_CONNECTION_ENABLED";
    private static final String EXTRA_READABILITY_ENABLED = ItemSyncAdapter.class.getName() +
            ".EXTRA_READABILITY_ENABLED";
    private static final String EXTRA_COMMENTS_ENABLED = ItemSyncAdapter.class.getName() +
            ".EXTRA_COMMENTS_ENABLED";
    private static final String EXTRA_NOTIFICATION_ENABLED = ItemSyncAdapter.class.getName() +
            ".EXTRA_NOTIFICATION_ENABLED";

    @UiThread
    static void initSync(Context context, @Nullable String itemId) {
        if (!Preferences.Offline.isEnabled(context)) {
            return;
        }
        Bundle extras = new Bundle();
        if (itemId != null) {
            extras.putString(ItemSyncAdapter.EXTRA_ID, itemId);
        }
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ItemSyncAdapter.EXTRA_CONNECTION_ENABLED,
                Preferences.Offline.currentConnectionEnabled(context));
        extras.putBoolean(ItemSyncAdapter.EXTRA_READABILITY_ENABLED,
                Preferences.Offline.isReadabilityEnabled(context));
        extras.putBoolean(ItemSyncAdapter.EXTRA_COMMENTS_ENABLED,
                Preferences.Offline.isCommentsEnabled(context));
        extras.putBoolean(ItemSyncAdapter.EXTRA_NOTIFICATION_ENABLED,
                Preferences.Offline.isNotificationEnabled(context));
        ContentResolver.requestSync(Application.createSyncAccount(),
                MaterialisticProvider.PROVIDER_AUTHORITY, extras);
    }

    private final HackerNewsClient.RestService mHnRestService;
    private final ReadabilityClient mReadabilityClient;
    private final SharedPreferences mSharedPreferences;
    private final NotificationManager mNotificationManager;
    private final NotificationCompat.Builder mNotificationBuilder;
    private final Map<String, SyncProgress> mSyncProgresses = new HashMap<>();
    private boolean mConnectionEnabled;
    private boolean mReadabilityEnabled;
    private boolean mCommentsEnabled;
    private boolean mNotificationEnabled;

    ItemSyncAdapter(Context context, RestServiceFactory factory,
                           ReadabilityClient readabilityClient) {
        super(context, true);
        mSharedPreferences = context.getSharedPreferences(
                context.getPackageName() + SYNC_PREFERENCES_FILE, Context.MODE_PRIVATE);
        mHnRestService = factory.create(HackerNewsClient.BASE_API_URL,
                HackerNewsClient.RestService.class, new BackgroundThreadExecutor());
        mReadabilityClient = readabilityClient;
        mNotificationManager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationBuilder = new NotificationCompat.Builder(getContext())
                .setLargeIcon(BitmapFactory.decodeResource(getContext().getResources(),
                        R.mipmap.ic_launcher))
                .setSmallIcon(R.drawable.ic_notification)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setAutoCancel(true);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        // assume that connection wouldn't change until we finish syncing
        mConnectionEnabled = extras.getBoolean(EXTRA_CONNECTION_ENABLED);
        mReadabilityEnabled = extras.getBoolean(EXTRA_READABILITY_ENABLED);
        mCommentsEnabled = extras.getBoolean(EXTRA_COMMENTS_ENABLED);
        mNotificationEnabled = extras.getBoolean(EXTRA_NOTIFICATION_ENABLED);
        if (extras.containsKey(EXTRA_ID)) {
            String id = extras.getString(EXTRA_ID);
            mSyncProgresses.put(id, new SyncProgress(id));
            sync(id, id);
        } else {
            syncDeferredItems();
        }
    }

    private void syncDeferredItems() {
        Set<String> itemIds = mSharedPreferences.getAll().keySet();
        for (String itemId : itemIds) {
            sync(itemId, null); // do not show notifications for deferred items
        }
    }

    private void sync(String itemId, final String progressId) {
        if (!mConnectionEnabled) {
            defer(itemId);
            return;
        }
        HackerNewsItem cachedItem;
        if ((cachedItem = getFromCache(itemId)) != null) {
            sync(cachedItem, progressId);
        } else {
            showNotification(progressId);
            // TODO defer on low battery as well?
            mHnRestService.networkItem(itemId).enqueue(new Callback<HackerNewsItem>() {
                @Override
                public void onResponse(Call<HackerNewsItem> call,
                                       retrofit2.Response<HackerNewsItem> response) {
                    HackerNewsItem item;
                    if ((item = response.body()) != null) {
                        sync(item, progressId);
                    }
                }

                @Override
                public void onFailure(Call<HackerNewsItem> call, Throwable t) {
                    notifyItem(progressId, itemId, null);
                }
            });
        }
    }

    @Synthetic
    void sync(@NonNull HackerNewsItem item, String progressId) {
        mSharedPreferences.edit().remove(item.getId()).apply();
        notifyItem(progressId, item.getId(), item);
        syncReadability(item);
        syncArticle(item);
        syncChildren(item);
    }

    private void syncReadability(@NonNull HackerNewsItem item) {
        if (mReadabilityEnabled && item.isStoryType()) {
            final String itemId = item.getId();
            mReadabilityClient.parse(itemId, item.getRawUrl());
            notifyReadability(itemId);
        }
    }

    private void syncArticle(@NonNull HackerNewsItem item) {
        if (item.isStoryType()) {
            ItemSyncService.WebCacheReceiver.initSave(getContext(), item.getUrl());
        }
    }

    private void syncChildren(@NonNull HackerNewsItem item) {
        if (mCommentsEnabled && item.getKids() != null) {
            for (long id : item.getKids()) {
                sync(String.valueOf(id), item.getId());
            }
        }
    }

    private void defer(String itemId) {
        mSharedPreferences.edit().putBoolean(itemId, true).apply();
    }

    private HackerNewsItem getFromCache(String itemId) {
        try {
            return mHnRestService.cachedItem(itemId).execute().body();
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isNotificationEnabled(@Nullable String progressId) {
        return mNotificationEnabled && progressId != null &&
                mSyncProgresses.containsKey(progressId);
    }

    @Synthetic
    void notifyItem(@Nullable String progressId, @NonNull String id,
                    @Nullable HackerNewsItem item) {
        if (isNotificationEnabled(progressId)) {
            mSyncProgresses.get(progressId).finishItem(id, item,
                    mCommentsEnabled && mConnectionEnabled,
                    mReadabilityEnabled && mConnectionEnabled);
            showNotification(progressId);
        }
    }

    private void notifyReadability(@Nullable String progressId) {
        if (isNotificationEnabled(progressId)) {
            mSyncProgresses.get(progressId).finishReadability();
            showNotification(progressId);
        }
    }

    private void showNotification(String progressId) {
        if (!isNotificationEnabled(progressId)) {
            return;
        }
        SyncProgress syncProgress = mSyncProgresses.get(progressId);
        if (syncProgress.getProgress() >= syncProgress.getMax()) {
            mSyncProgresses.remove(progressId);
            mNotificationManager.cancel(Integer.valueOf(progressId));
        } else {
            mNotificationManager.notify(Integer.valueOf(progressId), mNotificationBuilder
                    .setContentTitle(getContext().getString(R.string.download_in_progress))
                    .setContentText(syncProgress.title)
                    .setContentIntent(getItemActivity(progressId))
                    .setProgress(syncProgress.getMax(), syncProgress.getProgress(), false)
                    .setSortKey(progressId)
                    .build());
        }
    }

    private PendingIntent getItemActivity(String itemId) {
        return PendingIntent.getActivity(getContext(), 0,
                new Intent(Intent.ACTION_VIEW)
                        .setData(AppUtils.createItemUri(itemId))
                        .putExtra(ItemActivity.EXTRA_CACHE_MODE, ItemManager.MODE_CACHE)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_ONE_SHOT);
    }

    static class SyncProgress {
        private final String id;
        private Boolean self;
        private int totalKids, finishedKids;
        private Boolean readability;
        String title;

        @Synthetic
        SyncProgress(String id) {
            this.id = id;
        }

        int getMax() {
            return 1 + totalKids + (readability != null ? 1 : 0);
        }

        int getProgress() {
            return (self != null ? 1 : 0) + finishedKids + (readability != null && readability ? 1 :0);
        }

        @Synthetic
        void finishItem(@NonNull String id, @Nullable HackerNewsItem item,
                        boolean kidsEnabled, boolean readabilityEnabled) {
            if (TextUtils.equals(id, this.id)) {
                finishSelf(item, kidsEnabled, readabilityEnabled);
            } else {
                finishKid();
            }
        }

        @Synthetic
        void finishReadability() {
            readability = true;
        }

        private void finishSelf(@Nullable HackerNewsItem item, boolean kidsEnabled,
                                boolean readabilityEnabled) {
            self = item != null;
            title = item != null ? item.getTitle() : null;
            if (kidsEnabled && item != null && item.getKids() != null) {
                // fetch recursively but only notify for immediate children
                totalKids = item.getKids().length;
            } else {
                totalKids = 0;
            }
            if (readabilityEnabled) {
                readability = false;
            }
        }

        private void finishKid() {
            finishedKids++;
        }
    }

    static class BackgroundThreadExecutor implements Executor {

        @Override
        public void execute(@NonNull Runnable r) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }
    }
}
