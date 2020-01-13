package xyz.monkeytong.hongbao;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.service.notification.NotificationListenerService;

import java.util.List;

import xyz.monkeytong.hongbao.services.HongbaoNotificationService;
import xyz.monkeytong.hongbao.utils.SystemUtil;

/**
 * Created on 2020/1/13
 * Author: bigwang
 * Description:
 */
public class HBApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        if (SystemUtil.isInMainProcess(this)) {
            ensureNotificationServiceRunning();
        }
    }

    private void ensureNotificationServiceRunning() {
        ComponentName notificationService = new ComponentName(this, HongbaoNotificationService.class);
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        boolean running = false;
        List<ActivityManager.RunningServiceInfo> runningServices = manager.getRunningServices(Integer.MAX_VALUE);
        if (runningServices == null) {
            return;
        }
        for (ActivityManager.RunningServiceInfo service : runningServices) {
            if (service.service.equals(notificationService)) {
                if (service.pid == Process.myPid()) {
                    running = true;
                }
            }
        }
        if (running) {
            return;
        }
        toggleNotificationListenerService();
    }

    private void toggleNotificationListenerService() {
        ComponentName notificationService = new ComponentName(this, HongbaoNotificationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationListenerService.requestRebind(notificationService);
        } else {
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(notificationService, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(notificationService, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
