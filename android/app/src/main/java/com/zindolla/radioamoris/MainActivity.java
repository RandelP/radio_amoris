package com.zindolla.radioamoris;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media3.exoplayer.ExoPlayer;

import com.google.common.collect.ImmutableMap;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugins.GeneratedPluginRegistrant;

public class MainActivity extends FlutterActivity {
    public static final String TOSERVICE_TUNE_ID = "tune.id";
    public static final String TOSERVICE_TUNE_NAME = "tune.name";
    public static final String TOSERVICE_TUNE_URL = "tune.url";
    public static final String TOSERVICE_TUNE_LOGO = "tune.logo";
    public static final String TOSERVICE_TUNE_ASSETLOGO = "tune.asset.logo";
    private static final String PLAYER_CMD_METHOD_CHANNEL = "com.zindolla.radioamoris/audio";
    private static final String PLAYER_STATE_STREAM_CHANNEL = "com.zindolla.radioamoris/player-state";
    private static final String PLAYLIST_CTRL_STREAM_CHANNEL = "com.zindolla.radioamoris/playlist-ctrl";

    public final static String LOGTAG = MainActivity.class.getSimpleName();

    private MethodChannel channel;
    private static Context context;
    public static EventChannel.EventSink playerStateEvent;
    public static EventChannel.EventSink currentlyPlayingEvent;
    public static EventChannel.EventSink playlistCtrlEvent;

    public static final String PLAYER_STATE_LISTENER = "com.zindolla.radioamoris.PLAYER_STATE";
    public static final String PLAYLIST_CTRL = "com.zindolla.radioamoris.PLAYLIST_CTRL";

    private BroadcastReceiver playerStateListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals(PLAYER_STATE_LISTENER)) {
                int state = intent.getIntExtra("state", ExoPlayer.STATE_ENDED);
                int command = intent.getIntExtra("command", MyPlayerCommand.PLAY.ordinal());
                playerStateEvent.success(ImmutableMap.of("state", state, "command", command));
            }
        }
    };

    private BroadcastReceiver playlistCtrlListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Boolean isForward = intent.getBooleanExtra("isForward", true);
            if(intent.getAction().equals(PLAYLIST_CTRL)) {
                playlistCtrlEvent.success(isForward);
            }
        }
    };
    LocalBroadcastManager playerStateNotifManager;

    // NOTE: breaking change in Android 14 for foreground services, more details are here
    // https://developer.android.com/develop/background-work/services/foreground-services
    // https://medium.com/@domen.lanisnik/guide-to-foreground-services-on-android-9d0127dc8f9a
    private void foregroundService(Intent intent){
        Log.w(LOGTAG, String.format("starting foreground service with SDK: %s", Build.VERSION.SDK_INT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            this.startForegroundService(intent);
        } else {
            ContextCompat.startForegroundService(this, intent);
        }
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        GeneratedPluginRegistrant.registerWith(flutterEngine);
        MainActivity.context = getApplicationContext();
        super.configureFlutterEngine(flutterEngine);
        channel = new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), PLAYER_CMD_METHOD_CHANNEL);
        channel.setMethodCallHandler(
            (call, result) -> {
                Intent intent = new Intent( getApplicationContext(), MyRadioService.class );
                String action = null;
                switch (call.method) {
                    case "exoPlayerStart":
                        Integer tuneId = getMethodChannelVal("id", call, result);
                        String tuneUrl = getMethodChannelVal("url", call, result);
                        String tuneName = getMethodChannelVal("name", call, result);
                        String tuneLogo = call.argument("logo") == null ? "" : call.argument("logo");
                        String tuneAssetLogo = call.argument("assetLogo") == null ? "" : call.argument("assetLogo");

                        intent.putExtra(TOSERVICE_TUNE_ID, tuneId);
                        intent.putExtra(TOSERVICE_TUNE_NAME, tuneName);
                        intent.putExtra(TOSERVICE_TUNE_URL, tuneUrl);
                        intent.putExtra(TOSERVICE_TUNE_LOGO, tuneLogo);
                        intent.putExtra(TOSERVICE_TUNE_ASSETLOGO, tuneAssetLogo);
                        action = MyRadioService.AUDIO_START;
                        result.success(null);
                        break;
                    case "exoPlayerPause":
                        action = MyRadioService.AUDIO_PAUSE;
                        result.success(null);
                        break;
                    case "exoPlayerResume":
                        action = MyRadioService.AUDIO_RESUME;
                        result.success(null);
                        break;
                    default:
                        result.notImplemented();
                }
                if(action != null){
                    intent.setAction(action);
                    intent.putExtra(MyRadioService.BUNDLED_LISTENER, createReceiver(action));
                    foregroundService(intent);
                }
            }
        );
        new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), PLAYER_STATE_STREAM_CHANNEL).setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object args, final EventChannel.EventSink events) {
                        Log.w(LOGTAG, "Adding listener: playerStateEvent");
                        playerStateEvent = events;
                    }

                    @Override
                    public void onCancel(Object args) {
                        Log.w(LOGTAG, "Cancelling listener: playerStateEvent");
                        playerStateEvent = null;
                    }
                }
        );
        new EventChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), PLAYLIST_CTRL_STREAM_CHANNEL).setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object args, final EventChannel.EventSink events) {
                        Log.w(LOGTAG, "Adding listener: playlistCtrlEvent");
                        playlistCtrlEvent = events;
                    }

                    @Override
                    public void onCancel(Object args) {
                        Log.w(LOGTAG, "Cancelling listener: playlistCtrlEvent");
                        playlistCtrlEvent = null;
                    }
                }
        );
        playerStateNotifManager = LocalBroadcastManager.getInstance(this);
        IntentFilter istate = new IntentFilter();
        istate.addAction(PLAYER_STATE_LISTENER);
        playerStateNotifManager.registerReceiver(playerStateListener, istate);
        IntentFilter plylistCtrl = new IntentFilter();
        plylistCtrl.addAction(PLAYLIST_CTRL);
        playerStateNotifManager.registerReceiver(playlistCtrlListener, plylistCtrl);
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        playerStateNotifManager.unregisterReceiver(playerStateListener);
        playerStateNotifManager.unregisterReceiver(playlistCtrlListener);
        Intent intent = new Intent( getApplicationContext(), MyRadioService.class );
        intent.setAction(MyRadioService.EXIT_SERVICE);
        foregroundService(intent);
    }

    private <T> T getMethodChannelVal(String key, MethodCall call, MethodChannel.Result result) {
        T val = call.argument(key);
        if(val == null){
            result.error(call.method, String.format("no '%s' specified", key), null);
        }
        return val;
    }

    private ResultReceiver createReceiver(final String action){
        return new ResultReceiver(new Handler()) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);
                channel.invokeMethod((resultCode == Activity.RESULT_OK) ? action : MyRadioService.AUDIO_ERROR, 0);
            }
        };
    }
}
