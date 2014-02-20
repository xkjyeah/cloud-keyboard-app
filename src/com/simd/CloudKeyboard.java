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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class CloudKeyboard extends Activity implements android.view.View.OnClickListener {
    int port = 7777;
    LinearLayout layout;
    ServiceConnection serviceConnection;
    SharedKeyUpdateListener updateListener;
    
    private TextView txtSharedKey, txtServerURL;
    private TextView lblPrompt;
    private Button btnSaveSettings, btnCancelSettings, btnRenewKey;
    
    private RemoteKeyboard.Stub serviceBind;

    public static ArrayList<String> getNetworkAddresses() {
      ArrayList<String> addrs = new ArrayList<String>();
      try {
        Enumeration<NetworkInterface> ifaces =
          NetworkInterface.getNetworkInterfaces();
        while (ifaces.hasMoreElements()) {
          NetworkInterface iface = ifaces.nextElement();
          if ("lo".equals(iface.getName())) continue;
          Enumeration<InetAddress> addresses = iface.getInetAddresses();
          while (addresses.hasMoreElements()) {
            InetAddress addr = addresses.nextElement();
            addrs.add(addr.getHostAddress());
          }
        }
      } catch (SocketException e) {
        Debug.d("failed to get network interfaces");
      }
      return addrs;
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      setContentView(R.layout.pair);
      
      bindViews();
    }
    
    private void rebindService() {
      // Connect to the HttpService service
      // and retrieve the shared key

      serviceConnection = new ServiceConnection() {
        //@Override
        public void onServiceConnected(ComponentName name, IBinder service) {
          Debug.d("CloudKeyboard connected to HttpService.");
          
          serviceBind = (RemoteKeyboard.Stub) service;
          
          try {
            serviceBind.addSharedKeyUpdateListener( updateListener = new SharedKeyUpdateListener.Stub() {
              public void sharedKeyUpdated(String sharedKey) {
                updateViews();
              }
            });
          } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
          }
          updateViews();
        }
        //@Override
        public void onServiceDisconnected(ComponentName name) {
          Debug.d("WiFiInputMethod disconnected from HttpService.");
          
          serviceBind = null;
          updateViews();
        }
      };
      if (this.bindService(new Intent(this, HttpService.class),
          serviceConnection, BIND_AUTO_CREATE) == false) {
        throw new RuntimeException("failed to connect to HttpService");
      }
    }

    private void bindViews() {
      txtSharedKey = (TextView) findViewById(R.id.sharedKey);
      txtServerURL = (TextView) findViewById(R.id.serverURL);
      lblPrompt = (TextView) findViewById(R.id.prompt);
      btnSaveSettings = (Button) findViewById(R.id.saveSettings);
      btnCancelSettings = (Button) findViewById(R.id.cancelSettings);
      btnRenewKey = (Button) findViewById(R.id.renewKey);
      
      btnSaveSettings.setOnClickListener(this);
      btnCancelSettings.setOnClickListener(this);
      btnRenewKey.setOnClickListener(this);
    }
    
    private void updateViews() {
      String loginURL = "???", sharedKey = "???";
      
      try {
        loginURL = serviceBind.getLoginURL();
        sharedKey = serviceBind.getSharedKey();
      }
      catch (RemoteException e) {
        // ooops too bad
      }
      
      txtServerURL.setText(loginURL);
      txtSharedKey.setText( (sharedKey != null) ? sharedKey : "(Not logged in)");
    }

    @Override
    public void onResume() {
        super.onResume();
        
        rebindService();
    }

    @Override
    protected void onPause() {
      super.onPause();
      try {
        serviceBind.removeSharedKeyUpdateListener(updateListener);
        updateListener = null;
      }catch (RemoteException re) {
      }
      this.unbindService(serviceConnection);
    }

    @Override
    public void onClick(View arg0) {
      if (arg0 == btnRenewKey) {
        try {
          serviceBind.logout();
          serviceBind.login();
        }
        catch (RemoteException e) {
        }
        
        updateViews();
        return;
      }
      
      String loginURL = null;
      try {
        loginURL = serviceBind.getLoginURL();
      }catch(RemoteException e){}
      if (arg0 == btnSaveSettings &&
          loginURL != null &&
          txtServerURL.getText() != loginURL
          ) {
        // Save the new settings
        SharedPreferences.Editor ed = getSharedPreferences("default", Context.MODE_PRIVATE)
          .edit();
        ed.putString("serverURL", txtServerURL.getText().toString());
        
        if (ed.commit()) {
          /* Check if the server has changed...
           * if it has not, do nothing */
          try {
            serviceBind.logout();
            serviceBind.login();
          }
          catch (RemoteException e) {
          }
        }
        else {
          /* FIXME: Some dialog telling user the save failed */
        }
        updateViews();
        return;
      }
      
      if (arg0 == btnCancelSettings) { // Reset to original values
        txtServerURL.setText(loginURL);
      }
    }
}
