/**
 * WiFi Keyboard - Remote Keyboard for Android.
 * Copyright (C) 2011 Ivan Volosyuk
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.simd;

import java.util.HashSet;
import java.util.Set;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

public class HttpService extends Service {
  RemoteKeyListener listener;
  String htmlpage;
  int port;
  static KeyboardHttpPoller pollThread;
  private static boolean isRunning = false;
  private IntentFilter mWifiStateFilter;
  
  // Notification variables
  private String sharedKey, loginURL;
  
  // SharedKeyUpdates
  Set<SharedKeyUpdateListener> updateListeners;
  
  // Handler
  Handler handler;
  
  private PhoneStateListener dataListener = new PhoneStateListener() {
    @Override
    public void onDataConnectionStateChanged(int state) {
      super.onDataConnectionStateChanged(state);
      updateNotification();
    }
  };
  
  public HttpService() {
    mWifiStateFilter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
    mWifiStateFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
    mWifiStateFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
    
    updateListeners = new HashSet<SharedKeyUpdateListener>();
  }

  final IBinder mBinder = new RemoteKeyboard.Stub() {
    //@Override
    public void registerKeyListener(final RemoteKeyListener listener)
        throws RemoteException {
      HttpService.this.listener = listener;
    }
    //@Override
    public void unregisterKeyListener(final RemoteKeyListener listener)
        throws RemoteException {
      if (HttpService.this.listener == listener) {
        HttpService.this.listener = null;
      }
    }

    @Override
    public void startTextEdit(String content) throws RemoteException {
      // FIXME: add args
      if (pollThread != null)
        pollThread.notifyClient(content);
    }
    
    @Override
    public void stopTextEdit() throws RemoteException {
      // FIXME: add args
      if (pollThread != null)
        pollThread.notifyClient(null);
    }
    
    @Override
    public void addSharedKeyUpdateListener(SharedKeyUpdateListener listener)
        throws RemoteException {
      synchronized(updateListeners) {
        updateListeners.add(listener);
      }
    }

    @Override
    public void removeSharedKeyUpdateListener(SharedKeyUpdateListener listener)
        throws RemoteException {
      synchronized(updateListeners) {
        updateListeners.remove(listener);
      }
    }
    
    @Override
    public String getSharedKey() {
      return sharedKey;
    }
    
    @Override
    public String getLoginURL() {
      return HttpService.this.getLoginURL();
    }
    
    @Override
    public void login() {
      startPolling(HttpService.this);  
    }
    
    @Override
    public void logout() {
      wantLogout = true;
      if (HttpService.pollThread != null)
        HttpService.pollThread.finish();
    }
    
    public void resetPoll() {
      pollThread.resetPoll();
    }
    
    @Override
    public float getPollInterval() throws RemoteException {
      return HttpService.this.getPollInterval();
    }
  };
  
  /* Displays the notification -- called on Start,
   * when Wifi changed, when Data connection changed */
  private void updateNotification() {
    Intent notificationIntent = new Intent(this, CloudKeyboard.class);
    PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
    
    Context context = getApplicationContext();
    NotificationManager mgr =
      (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    NotificationCompat.Builder b = new NotificationCompat.Builder(context);

    b.setSmallIcon(R.drawable.icon)
    .setContentTitle("Cloud Keyboard")
    .setContentText(getString(R.string.notification_shared_key, sharedKey, loginURL))
    .setContentIntent(contentIntent);
    
    Notification n = b.build();
    
    mgr.notify(0, n);
  }
  
  protected float getPollInterval() {
    return getSharedPreferences("default", Context.MODE_PRIVATE)
        .getFloat("pollInterval", 10);
  }

  private final BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      updateNotification();
    }
  };
  public boolean wantLogout = false;
  
  @Override
  public void onCreate() {
    super.onCreate();
    Log.d("wifikeyboard", "onCreate()");
    if (isRunning) return;
    
    registerReceiver(mWifiStateReceiver, mWifiStateFilter);
    TelephonyManager t = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
    t.listen(dataListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
    
    this.handler = new Handler();

    startPolling(this);
  }
  
  private static void removeNotification(Context context) {
    NotificationManager mgr =
      (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
    mgr.cancelAll();
  }
  
  @Override
  public void onDestroy() {
    isRunning = false;
    onServerFinish = null;
//    stopForeground(true);
    Log.d("wifikeyboard", "onDestroy()");
    pollThread.finish();
    unregisterReceiver(mWifiStateReceiver);
    TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
    tm.listen(dataListener, PhoneStateListener.LISTEN_NONE);
    removeNotification(this);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return mBinder;
  }

  private static Runnable onServerFinish = null;
  
  public static void doStartPolling(HttpService context) {
    pollThread = new KeyboardHttpPoller(context);
    
    pollThread.start();
    
//    ServerSocketChannel socket = makeSocket(context);
//    context.port = socket.socket().getLocalPort();
//    server = new KeyboardHttpServer(context, socket);
//    Editor editor = context.getSharedPreferences("port", MODE_PRIVATE).edit();
//    editor.putInt("port", context.port);
//    editor.commit();
//    try {
//      if (context.portUpdateListener != null) {
//        context.portUpdateListener.portUpdated(context.port);
//      }
//    } catch (RemoteException e) {
//      Log.e("wifikeyboard", "port update failure", e);
//    }
//    context.updateNotification(true);
//    server.start();
  }
  
  public static void startPolling(final HttpService context) {
    if (pollThread == null) {
      doStartPolling(context);
    } else {
      onServerFinish = new Runnable() {
        @Override
        public void run() {
          doStartPolling(context);
        }
      };
    }
  }
  
  public void networkServerFinished() {
    pollThread = null;
    if (onServerFinish != null) {
      onServerFinish.run();
    }
  }
  
  public void notifySharedKey(final String sharedKey) {
    this.sharedKey = sharedKey;
    this.handler.post( new Runnable() {
      public void run() {
        synchronized(HttpService.this.updateListeners) {
          Set<SharedKeyUpdateListener> toRemove = new HashSet<SharedKeyUpdateListener>();
          
          for (SharedKeyUpdateListener skul : updateListeners) {
            try {
              skul.sharedKeyUpdated(sharedKey);
            } catch (DeadObjectException doe) {
              toRemove.add(skul);
            } catch (RemoteException re) {
            }
          }
          updateListeners.removeAll(toRemove);
        }
      }
    });
    this.updateNotification();
  }
  
  public String getLoginURL() {
    return getSharedPreferences("default", Context.MODE_PRIVATE)
        .getString("serverURL", "http://localhost:8080/");
  }
}
