package com.mqltv;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class MqlNotificationListenerService extends NotificationListenerService {

    public static final String ACTION_NOTIFICATION_COUNT_CHANGED = "com.mqltv.NOTIFICATION_COUNT_CHANGED";
    public static final String EXTRA_COUNT = "count";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        broadcastNotificationCount();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        broadcastNotificationCount();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        broadcastNotificationCount();
    }

    private void broadcastNotificationCount() {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            int count = 0;
            if (activeNotifications != null) {
                for (StatusBarNotification sbn : activeNotifications) {
                    if (sbn == null || sbn.getNotification() == null) continue;
                    android.app.Notification n = sbn.getNotification();
                    int flags = n.flags;
                    boolean isOngoing = (flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
                    boolean isForegroundService = (flags & android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0;
                    boolean isGroupSummary = (flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0;
                    boolean isNoClear = (flags & android.app.Notification.FLAG_NO_CLEAR) != 0;
                    
                    String pkg = sbn.getPackageName() != null ? sbn.getPackageName() : "";
                    
                    // Abaikan notifikasi sistem, un-clearable, dan package internal android
                    if (!isOngoing && !isForegroundService && !isGroupSummary && !isNoClear && !pkg.equals("android") && !pkg.equals("com.android.systemui")) {
                        android.util.Log.d("MQLTV_NOTIF", "Dihitung: " + pkg);
                        count++;
                    } else {
                        android.util.Log.d("MQLTV_NOTIF", "Diabaikan: " + pkg + " (Ongoing:" + isOngoing + " NoClear:" + isNoClear + ")");
                    }
                }
            }
            
            Intent intent = new Intent(ACTION_NOTIFICATION_COUNT_CHANGED);
            intent.putExtra(EXTRA_COUNT, count);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
