package xyz.monkeytong.hongbao.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.graphics.Path;
import android.preference.PreferenceManager;

import androidx.annotation.RequiresApi;
import androidx.core.view.accessibility.AccessibilityEventCompat;
import androidx.core.view.accessibility.AccessibilityWindowInfoCompat;

import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import xyz.monkeytong.hongbao.R;
import xyz.monkeytong.hongbao.utils.HongbaoSignature;
import xyz.monkeytong.hongbao.utils.PowerUtil;

import java.util.List;

public class HongbaoService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "HongbaoService";
    private static final String WECHAT_DETAILS_EN = "Details";
    private static final String WECHAT_DETAILS_CH = "红包详情";
    private static final String WECHAT_DETAILS_2_CH = "红包记录";
    private static final String WECHAT_OPENED = "已存入零钱";
    private static final String WECHAT_BETTER_LUCK_EN = "Better luck next time!";
    private static final String WECHAT_BETTER_LUCK_CH = "手慢了";
    private static final String WECHAT_BETTER_LUCK_2_CH = "手慢了，红包派完了";
    private static final String WECHAT_BETTER_LUCK_3_CH = "看看大家的手气";
    private static final String WECHAT_EXPIRES_CH = "已超过24小时";
    private static final String WECHAT_EXPIRES_2_CH = "过期";
    private static final String WECHAT_VIEW_SELF_CH = "查看红包";
    private static final String WECHAT_VIEW_OTHERS_CH = "领取红包";
    private static final String WECHAT_VIEW_ALL_CH = "微信红包";
    private static final String WECHAT_VIEW_WORK_CH = "红包";
    private static final String WECHAT_NOTIFICATION_TIP = "[微信红包]";
    private static final String WECHAT_LUCKMONEY_RECEIVE_ACTIVITY = ".plugin.luckymoney.ui";//com.tencent.mm/.plugin.luckymoney.ui.En_fba4b94f  com.tencent.mm/com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI
    private static final String WECHAT_LUCKMONEY_RECEIVE_UI_ACTIVITY = "LuckyMoneyReceiveUI";
    private static final String WECHAT_LUCKMONEY_NOT_HOOK_RECEIVE_UI_ACTIVITY = "LuckyMoneyNotHookReceiveUI";
    private static final String WECHAT_LUCKMONEY_DETAIL_ACTIVITY = "LuckyMoneyDetailUI";
    private static final String WECHAT_LUCKMONEY_GENERAL_ACTIVITY = "LauncherUI";
    private static final String WECHAT_LUCKMONEY_CHATTING_ACTIVITY = "ChattingUI";
    private String currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;

    private AccessibilityNodeInfo /*rootNodeInfo,*/ mReceiveNode, mUnpackNode;
    private boolean mLuckyMoneyPicked, mLuckyMoneyReceived;
    private int mUnpackCount = 0;
    private boolean mMutex = false, mListMutex = false, mChatMutex = false, mOpened = false;
    private HongbaoSignature signature = new HongbaoSignature();

    private PowerUtil powerUtil;
    private SharedPreferences sharedPreferences;

    private Handler mHandler;

    /**
     * AccessibilityEvent
     *
     * @param event 事件
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

        if (sharedPreferences == null) return;

        setCurrentActivityName(event);
        Log.d(TAG, "time: " + event.getEventTime() + "  type: " + event.getEventType() + " content type: " + event.getContentChangeTypes());

        /* 检测通知消息 */
        if (!mMutex) {
            if (sharedPreferences.getBoolean("pref_watch_notification", false) && watchNotifications(event))
                return;
            if (sharedPreferences.getBoolean("pref_watch_list", false) && watchList(event)) return;
            mListMutex = false;
        }

        if (!mChatMutex) {
            mChatMutex = true;
            if (sharedPreferences.getBoolean("pref_watch_chat", false)) watchChat(event);
            mChatMutex = false;
        }
    }

    private void watchChat(AccessibilityEvent event) {
//        this.rootNodeInfo = getRootInActiveWindow();

//        if (rootNodeInfo == null) return;

        mReceiveNode = null;
        mUnpackNode = null;

        checkNodeInfo(event.getEventType());

        /* 如果已经接收到红包并且还没有戳开 */
        Log.d(TAG, "watchChat mLuckyMoneyReceived:" + mLuckyMoneyReceived + " mLuckyMoneyPicked:" + mLuckyMoneyPicked + " mReceiveNode:" + mReceiveNode);
        if (mLuckyMoneyReceived && (mReceiveNode != null)) {
            mMutex = true;
            mOpened = true;
            mReceiveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            mLuckyMoneyReceived = false;
            mLuckyMoneyPicked = true;
            if (mUnpackNode == null) {
                mUnpackCount += 1;
            }
            return;
        }
        /* 如果戳开但还未领取 */
        Log.d(TAG, "戳开红包！" + " mUnpackCount: " + mUnpackCount + " mUnpackNode: " + mUnpackNode);
        if (mUnpackCount >= 1 && (mUnpackNode != null) || canOpen(event)) {
            int delayFlag = sharedPreferences.getInt("pref_open_delay", 0) * 1000;
            if (delayFlag != 0) {
                getHandler().postDelayed(
                        new Runnable() {
                            public void run() {
                                try {
                                    openPacket();
                                } catch (Exception e) {
                                    mMutex = false;
                                    mLuckyMoneyPicked = false;
                                    mUnpackCount = 0;
                                    mUnpackNode = null;
                                }
                            }
                        },
                        delayFlag);
            } else {
                openPacket();
            }
        }

    }

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler();
        }
        return mHandler;
    }

    private void openPacket() {
        if (mUnpackCount >= 1 && (mUnpackNode != null)) {
            Log.d(TAG, "openPacket！");
            mOpened = true;
            mUnpackNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            mUnpackCount = 0;
            mUnpackNode = null;
        } else if (mUnpackCount >= 1) {
            Log.d(TAG, "openPacket！");
            if (android.os.Build.VERSION.SDK_INT > Build.VERSION_CODES.M && currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)) {
                mOpened = true;
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                float dpi = metrics.densityDpi;
                Log.d(TAG, "dpi: " + dpi);
                Path path = new Path();
                path.moveTo(metrics.widthPixels * 0.5f, metrics.heightPixels * 0.6f);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                GestureDescription gestureDescription = builder.addStroke(new GestureDescription.StrokeDescription(path, 450, 50)).build();
                dispatchGesture(gestureDescription, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        Log.d(TAG, "onCompleted");
                        mMutex = false;
                        mUnpackCount = 0;
                        mUnpackNode = null;
                        super.onCompleted(gestureDescription);
                        onGestureEnd();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        Log.d(TAG, "onCancelled");
                        mMutex = false;
                        mUnpackCount = 0;
                        mUnpackNode = null;
                        super.onCancelled(gestureDescription);
                        onGestureEnd();
                    }
                }, null);

            }
        }
    }

    private void onGestureEnd() {
        getHandler().postDelayed(new Runnable() {
            @Override
            public void run() {
                /* 戳开红包，红包已被抢完，遍历节点匹配“红包详情”和“手慢了” */
                boolean hasNodes = hasOneOfThoseNodes(getRootInActiveWindow(),
                        WECHAT_BETTER_LUCK_CH, WECHAT_BETTER_LUCK_2_CH, WECHAT_DETAILS_CH, WECHAT_DETAILS_2_CH, WECHAT_BETTER_LUCK_3_CH,
                        WECHAT_BETTER_LUCK_EN, WECHAT_DETAILS_EN, WECHAT_EXPIRES_CH, WECHAT_EXPIRES_2_CH);
                Log.d(TAG, "checkNodeInfo  hasNodes:" + hasNodes + " opened: " + mOpened + " mMutex:" + mMutex + " name: " + currentActivityName);
                if (hasNodes
                        && (currentActivityName.contains(WECHAT_LUCKMONEY_DETAIL_ACTIVITY)
                        || currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY))) {
                    mMutex = false;
                    mLuckyMoneyPicked = false;
                    mUnpackCount = 0;
                    mUnpackNode = null;
                    if (mOpened && sharedPreferences.getBoolean("pref_open_after_back", false)) {
                        mOpened = false;
                        Log.d(TAG, "back click");
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                    signature.commentString = generateCommentString();
                }
            }
        }, 100);
    }

    private boolean canOpen(AccessibilityEvent event) {
        AccessibilityNodeInfo rootNodeInfo = getRootInActiveWindow();
        if ((currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_UI_ACTIVITY) || currentActivityName.contains(WECHAT_LUCKMONEY_NOT_HOOK_RECEIVE_UI_ACTIVITY))) {
            if (rootNodeInfo == null) {
                return true;
            }
            boolean hasNodes = this.hasOneOfThoseNodes(rootNodeInfo, WECHAT_OPENED,
                    WECHAT_BETTER_LUCK_CH, WECHAT_BETTER_LUCK_2_CH, WECHAT_BETTER_LUCK_3_CH,
                    WECHAT_DETAILS_CH, WECHAT_DETAILS_2_CH, WECHAT_BETTER_LUCK_EN, WECHAT_DETAILS_EN, WECHAT_EXPIRES_CH, WECHAT_EXPIRES_2_CH);
            if (hasNodes) {
                return false;
            }
            if (mUnpackNode == null) {
                /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
                AccessibilityNodeInfo node2 = findOpenButton(rootNodeInfo);
                Log.d(TAG, "node2 " + node2);
                if (node2 != null && "android.widget.Button".equals(node2.getClassName()) && currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)
                        && (mUnpackNode == null || !mUnpackNode.equals(node2))) {
                    mUnpackNode = node2;
                    mUnpackCount += 1;
                }
            }
            return true;
        }
        return false;
    }

    private void setCurrentActivityName(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
//        try {
        ComponentName componentName = new ComponentName(
                event.getPackageName().toString(),
                event.getClassName().toString()
        );
//            getPackageManager().getActivityInfo(componentName, 0);
        currentActivityName = componentName.flattenToShortString();
        Log.d(TAG, currentActivityName);
//        } catch (PackageManager.NameNotFoundException e) {
//            currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;
//        }
    }

    private boolean watchList(AccessibilityEvent event) {
        if (mListMutex || !currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY) || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            return false;
        mListMutex = true;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> chatItems = root.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/bah");
        for (AccessibilityNodeInfo chatItem : chatItems) {
            List<AccessibilityNodeInfo> unreads = chatItem.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/op");
            AccessibilityNodeInfo info;
            if (unreads.isEmpty() || (info = unreads.get(0)) == null) {
                continue;
            }
            if (TextUtils.isEmpty(info.getText())) {
                continue;
            }
            List<AccessibilityNodeInfo> contents = chatItem.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/bal");
            if (contents.isEmpty() || (info = contents.get(0)) == null) {
                continue;
            }
            if (info.getText() != null && info.getText().toString().contains(WECHAT_NOTIFICATION_TIP)) {
                chatItem.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        return false;
    }

    private boolean watchNotifications(AccessibilityEvent event) {
        // Not a notification
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            return false;

        // Not a hongbao
        String tip = event.getText().toString();
        if (!tip.contains(WECHAT_NOTIFICATION_TIP)) return true;

        Parcelable parcelable = event.getParcelableData();
        if (parcelable instanceof Notification) {
            Notification notification = (Notification) parcelable;
            try {
                /* 清除signature,避免进入会话后误判 */
                signature.cleanSignature();

                notification.contentIntent.send();
            } catch (PendingIntent.CanceledException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    private AccessibilityNodeInfo findOpenButton(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        //非layout元素
        if (node.getChildCount() == 0) {
            if ("android.widget.Button".equals(node.getClassName()))
                return node;
            else
                return null;
        }

        //layout元素，遍历找button
        AccessibilityNodeInfo button;
        for (int i = 0; i < node.getChildCount(); i++) {
            button = findOpenButton(node.getChild(i));
            if (button != null)
                return button;
        }
        return null;
    }

    public AccessibilityNodeInfo getNewHongbaoNode(AccessibilityNodeInfo node) {
        try {
            /* The hongbao container node. It should be a LinearLayout. By specifying that, we can avoid text messages. */
            AccessibilityNodeInfo hongbaoNode = node.getParent();
            if (hongbaoNode == null) {
                return null;
            }
            CharSequence name = hongbaoNode.getClassName();
            if (!"android.widget.FrameLayout".equals(name == null ? null : name.toString()))
                return null;

            /* The text in the hongbao. Should mean something. */
            int count = hongbaoNode.getChildCount();
            String hongbaoContent = count >= 1 ? hongbaoNode.getChild(0).getText().toString() : null;
            if ("查看红包".equals(hongbaoContent)) {
                return null;
            }
            String excludeWords = sharedPreferences.getString("pref_watch_exclude_words", "");
            if (!this.signature.generateSignature(hongbaoContent, excludeWords)) {
                Log.d(TAG, "content [" + hongbaoContent + "] exclude");
                return null;
            }
            if (count > 1) {
                hongbaoContent = hongbaoNode.getChild(1).getText().toString();
                if (TextUtils.isEmpty(hongbaoContent) || hongbaoContent.contains("已被领完") || hongbaoContent.contains("已领取") || hongbaoContent.contains("已过期"))
                    return null;
            }
            boolean self = sharedPreferences.getBoolean("pref_watch_self", false);
            if (!self) {
                Rect bounds = new Rect();
                hongbaoNode.getBoundsInScreen(bounds);
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                if (bounds.centerX() > metrics.widthPixels / 2) {
                    return null;
                }
            }
            return hongbaoNode;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void checkNodeInfo(final int eventType) {
        AccessibilityNodeInfo rootNodeInfo = getRootInActiveWindow();
        if (rootNodeInfo == null) return;


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (signature.commentString != null) {
                sendComment();
                signature.commentString = null;
            }
        }

        /* 聊天会话窗口，遍历节点匹配“微信红包”，“领取红包”和"查看红包" */
        AccessibilityNodeInfo node1 = getTheLastNode(WECHAT_VIEW_ALL_CH, WECHAT_VIEW_WORK_CH, WECHAT_VIEW_OTHERS_CH, WECHAT_VIEW_SELF_CH);
        if (node1 != null
                && (currentActivityName.contains(WECHAT_LUCKMONEY_CHATTING_ACTIVITY) || currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY))) {
            node1 = getNewHongbaoNode(node1);
            if (node1 != null) {
                mLuckyMoneyReceived = true;
                mReceiveNode = node1;
                Log.d("sig", this.signature.toString());
            }
            return;
        }

//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
//            List<AccessibilityWindowInfo> windows = getWindows();
//            Log.d(TAG, "window size:" + windows.size());
//            for (AccessibilityWindowInfo window : windows){
//                AccessibilityNodeInfo nodeInfo = window.getRoot();
//                if (nodeInfo == null) {
//                    Log.d(TAG, "window:" + window.getId() + " null");
//                    continue;
//                }
//                AccessibilityNodeInfo node =  findOpenButton(nodeInfo);
//                Log.d(TAG, "window:" + window.getId() + " node: " + node);
//            }
//
//        }
        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        AccessibilityNodeInfo node2 = findOpenButton(rootNodeInfo);
        Log.d(TAG, "checkNodeInfo  node2 " + node2);
        if (node2 != null && "android.widget.Button".equals(node2.getClassName()) && currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)
                && (mUnpackNode == null || !mUnpackNode.equals(node2))) {
            mUnpackNode = node2;
            mUnpackCount += 1;
            return;
        }

//        if (mOpened ) {
//            getHandler().postDelayed(new Runnable() {
//                @Override
//                public void run() {
        /* 戳开红包，红包已被抢完，遍历节点匹配“红包详情”和“手慢了” */
        boolean hasNodes = hasOneOfThoseNodes(rootNodeInfo, WECHAT_OPENED,
                WECHAT_BETTER_LUCK_CH, WECHAT_BETTER_LUCK_2_CH, WECHAT_DETAILS_CH, WECHAT_DETAILS_2_CH, WECHAT_BETTER_LUCK_3_CH,
                WECHAT_BETTER_LUCK_EN, WECHAT_DETAILS_EN, WECHAT_EXPIRES_CH, WECHAT_EXPIRES_2_CH);
        Log.d(TAG, "checkNodeInfo  hasNodes:" + hasNodes + " opened: " + mOpened + " mMutex:" + mMutex + " name: " + currentActivityName);
        if (hasNodes && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && (currentActivityName.contains(WECHAT_LUCKMONEY_DETAIL_ACTIVITY)
                || currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY))) {
            mMutex = false;
            mLuckyMoneyPicked = false;
            mUnpackCount = 0;
            mUnpackNode = null;
            if (mOpened && sharedPreferences.getBoolean("pref_open_after_back", false)) {
                mOpened = false;
                Log.d(TAG, "back click");
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            signature.commentString = generateCommentString();
        }
//                }
//            }, 100);
//        }

    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void sendComment() {
        try {
            AccessibilityNodeInfo outNode =
                    getRootInActiveWindow().getChild(0).getChild(0);
            AccessibilityNodeInfo nodeToInput = outNode.getChild(outNode.getChildCount() - 1).getChild(0).getChild(1);

            if ("android.widget.EditText".equals(nodeToInput.getClassName())) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, signature.commentString);
                nodeToInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            }
        } catch (Exception e) {
            // Not supported
        }
    }


    private boolean hasOneOfThoseNodes(AccessibilityNodeInfo rootNodeInfo, String... texts) {
        List<AccessibilityNodeInfo> nodes;
        for (String text : texts) {
            if (text == null) continue;

            nodes = rootNodeInfo.findAccessibilityNodeInfosByText(text);

            if (nodes != null && !nodes.isEmpty()) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo getTheLastNode(String... texts) {
        AccessibilityNodeInfo rootNodeInfo = getRootInActiveWindow();
        if (rootNodeInfo == null) {
            return null;
        }
        int bottom = 0;
        AccessibilityNodeInfo lastNode = null, tempNode;
        List<AccessibilityNodeInfo> nodes;
        for (String text : texts) {
            if (text == null) continue;
            nodes = rootNodeInfo.findAccessibilityNodeInfosByText(text);

            if (nodes != null && !nodes.isEmpty()) {
                tempNode = nodes.get(nodes.size() - 1);
                if (tempNode == null) return null;
                Rect bounds = new Rect();
                tempNode.getBoundsInScreen(bounds);
                if (bounds.bottom > bottom) {
                    bottom = bounds.bottom;
                    lastNode = tempNode;
                    signature.others = text.equals(WECHAT_VIEW_OTHERS_CH);
                }
            }
        }
        return lastNode;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        this.watchFlagsFromPreference();
    }

    private void watchFlagsFromPreference() {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        this.powerUtil = new PowerUtil(this);
        Boolean watchOnLockFlag = sharedPreferences.getBoolean("pref_watch_on_lock", false);
        this.powerUtil.handleWakeLock(watchOnLockFlag);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("pref_watch_on_lock")) {
            Boolean changedValue = sharedPreferences.getBoolean(key, false);
            this.powerUtil.handleWakeLock(changedValue);
        }
    }


    @Deprecated
    private String generateCommentString() {
//        if (!signature.others) return null;
//
//        Boolean needComment = sharedPreferences.getBoolean("pref_comment_switch", false);
//        if (!needComment) return null;
//
//        String[] wordsArray = sharedPreferences.getString("pref_comment_words", "").split(" +");
//        if (wordsArray.length == 0) return null;
//
//        Boolean atSender = sharedPreferences.getBoolean("pref_comment_at", false);
//        if (atSender) {
//            return "@" + signature.sender + " " + wordsArray[(int) (Math.random() * wordsArray.length)];
//        } else {
//            return wordsArray[(int) (Math.random() * wordsArray.length)];
//        }
        return "thanks";
    }

    @Override
    public void onInterrupt() {
        Toast.makeText(this, R.string.interrupt, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        this.powerUtil.handleWakeLock(false);
        super.onDestroy();
    }
}