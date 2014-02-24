/**
 * Cloud Keyboard - Remote Keyboard for Android.
 * Copyright (C) 2014 Daniel Sim
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

import static com.simd.KeycodeConvertor.convertKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpEntity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.simd.MessageAck.Result;

@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class KeyboardHttpPoller extends Thread {

  // thread loop controller
  private boolean finished = false;
  
  // private for network thread
  private Selector selector;
  
  // WiFiInputMethod
  private HttpService service;
  
  public final static int FOCUS = 1024;
  
  public KeyboardHttpPoller(HttpService wfim) {
    this.service = wfim;
  }
  
  ActionRunner actionRunner = new ActionRunner();
  
  
  /**
   * Invoke from network thread and queue for execution on
   * main thread (synchronized).
   * @param action to run on main thread
   * @return object return by the action
   */
  public Object runAction(Action action) {
    actionRunner.setAction(action);
    service.handler.post(actionRunner);
    return actionRunner.waitResult();
  }

  interface Update extends Runnable {}
  
  ArrayList<Update> pendingUpdates = new ArrayList<Update>();
  
  public void postUpdate(Update update) {
    pendingUpdates.add(update);
    try {
    selector.wakeup();
    } catch (Throwable t) {}
  }
  
  private void showErrorMessage(String title, String message) {
    AlertDialog.Builder b = new AlertDialog.Builder(service);
    
    b.setMessage(message).setTitle(title);
    
    b.show();
  }
  
  private JSONObject jsonFromHttpEntity(HttpEntity he) {
    try {
      return jsonFromInputStream(he.getContent());
    }
    catch (IOException e) {
      return null;
    }
  }
  private JSONObject jsonFromInputStream(InputStream is) {
    JSONObject replyBody = new JSONObject();
    try {
      // TODO: limit input size
      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      StringBuilder sb = new StringBuilder();
      String line;
      
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      
      replyBody = new JSONObject(sb.toString());
    }
    catch (JSONException jse) {
      Log.e("cloudkeyboard", "Could not parse reply from server as JSON: " + jse.toString());
      // return a blank object...
      replyBody = new JSONObject();
    }
    catch (IOException ioe) {
      Log.e("cloudkeyboard", "I/O error while reading reply from server: " + ioe.toString());
      replyBody = new JSONObject();
    }
    return replyBody;
  }
  
  /**
   * Input: nil
   * 
   * Output: Shared key
   * 
   * @return
   */
  private String doLogin(URL base_url) throws IOException {
    HttpURLConnection cl = (HttpURLConnection) new URL(base_url, "login").openConnection();
    cl.setRequestMethod("POST");
    cl.setDoOutput(true);
    OutputStream os = cl.getOutputStream();
    OutputStreamWriter osw = new OutputStreamWriter(os, "UTF-8");
    
    List<Pair<String,String>> nameValuePairs = new ArrayList<Pair<String,String>>();
    
    for (Pair<String,String> p : nameValuePairs) {
      osw.write( URLEncoder.encode( p.first, "UTF-8" ) );
      osw.write("=");
      osw.write( URLEncoder.encode( p.second, "UTF-8") );
    }
    
    
    if (cl.getResponseCode() != 200) {
      showErrorMessage(
          "Error while contacting server",
          "Server returned status code " + cl.getResponseCode()
          );
      return null;
    }

    // if successful, we parse the body to obtain the shared
    // key, then we notify WiFiKeyboard to show the shared key
    JSONObject responseBody = null;
    try {
      responseBody = jsonFromInputStream(cl.getInputStream());
    }
    catch (Exception e) {
      return null;
    }
    
    String sharedKey = responseBody.optString("shared-key");
    if (sharedKey == null)
      showErrorMessage("Error while contacting server",
          "Did not understand reply from server");
    return sharedKey;
  }
  

  private void doLogout(URL base_url) throws IOException {
    HttpURLConnection cl = (HttpURLConnection) new URL(base_url, "logout").openConnection();
    
    if (cl.getResponseCode() != 200) {
      Log.e(
          "Error while logging out",
          "Server returned status code " + cl.getResponseCode()
          );
      return;
    }
  }
  
  /** 
   * Updates the session cookie from what we have in storage
   * 
   * Creates a new cookie manager with a cookie from the session
   * and sets this cookie manager as the cookie handler for the
   * application.
   * 
   * @param cl
   */
  private CookieManager doSessionCookie(URL base_url) {
    SharedPreferences prefs = service.getSharedPreferences("default", Context.MODE_PRIVATE);
    String sessionCookie;
    
    CookieManager cookieManager = new CookieManager();
    CookieHandler.setDefault(cookieManager);

    sessionCookie = prefs.getString("session_cookie", null);
    
    if (sessionCookie != null) {
      // TODO:
      List<HttpCookie> cookies = new ArrayList<HttpCookie>();
      try {
        cookies = HttpCookie.parse(sessionCookie);
      } catch (IllegalArgumentException iae) {
        // Watch out for illegal values stored in preferences
      }
      
      // Put the cookies in the cookie store.
      // But the base url must be stripped of extra path
      URI u = null;
      try {
        u = new URL(base_url.getProtocol(), base_url.getHost(),
          base_url.getPort(), "/").toURI();
        
        for (HttpCookie cookie : cookies) {
          cookie.setPath("/"); // FIXME: save and read this setting
          cookie.setDomain(u.getHost());
          cookieManager.getCookieStore().add(u, cookie);
          Log.d("cloudkb", "Loaded: " + cookie.toString());
          Log.d("cloudkb", Long.toString(cookie.getMaxAge()));
          Log.d("cloudkb", (cookie.getDomain() == null) ? "(null)" : cookie.getDomain());
          Log.d("cloudkb", (cookie.getPortlist() == null) ? "(null)" : cookie.getPortlist());
        }
      }
      catch (URISyntaxException e) {
        e.printStackTrace();
      } catch (MalformedURLException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    
    return cookieManager;
  }
  
  private int pollInterval = 1;
  
  private void doPoll(URL u) throws IOException {
    ArrayList<JSONObject> replies = new ArrayList<JSONObject>();
    // now, regularly poll the destination server
    
    pollInterval = 1;
    
    while (true) {
      HttpURLConnection cl = (HttpURLConnection) new URL(u, "poll").openConnection();
      cl.setRequestMethod("POST");
      cl.setDoOutput(true);
      cl.addRequestProperty("Content-type", "application/json");
      OutputStream os = cl.getOutputStream();
      
      JSONObject requestBody = new JSONObject();
      JSONArray repl = new JSONArray();
      JSONObject replyBody = null;
      JSONArray events = null;
      
      // TODO: you don't need a queue here
      // since processEvents() runs in the same thread
      // instead, manage events such that if an exception
      // occurs, you can still re-send...
      for (JSONObject reply : replies) {
        repl.put(reply);
      }
      try {
        OutputStreamWriter osw = new OutputStreamWriter(os, "UTF-8");
        
        requestBody.put("replies", repl);

        osw.write(requestBody.toString(2));
        osw.close();
        
        int responseCode = cl.getResponseCode();
       
        if (responseCode != 200) {
          Log.e("cloudkeyboard", "poll returned response " + responseCode);
        }
        else {
          // Otherwise, the poll is a success. We can discard the replies
          // and process the new events.
          replies.clear(); // Oh well, no error-handling/retries for now
          
          // Now read the JSON reply... if it is a JSON reply
          replyBody = jsonFromInputStream(cl.getInputStream());
        }
      }
      catch (JSONException jse) {
        // the replies are giving us problems. so...
        replies.clear();
        Log.e("cloudkeyboard", "Why is there a JSON error here?" + jse.toString());
      }
      catch (UnsupportedEncodingException uee) {
        Log.e("cloudkeyboard", uee.toString());
      }
      catch (IOException ioe) {
        pollInterval = Math.min(pollInterval, 1024);
      }

      if (replyBody != null &&
          replyBody.has("events") &&
          (events = replyBody.optJSONArray("events")) != null &&
          events.length() != 0) {
        pollInterval = 1;
        
        replies.addAll(processEvents(events));
      }
      else {
        if (pollInterval*2 < service.getPollInterval() * 1000) {
          pollInterval *= 2;
        }
      }
      
      // Sleep for the specified poll interval
      try {
        if (pollInterval > 1)
          sleep(pollInterval - 1);
      }
      catch (InterruptedException ie) {
        // comes here, e.g. when user control
        // gets focus and thread's interrupt function is called
      }

      // check if we are done
      synchronized(this) {
        if (this.finished)
          break;
      }
    } /* while(true) */
    
    /* connection closed or something! */
  }
  

  /** 
   * Forces an immediate poll, e.g. on a focus event of a widget,
   * by interrupting the polling thread.
   * 
   * Called from InputMethod side.
   * 
   */
  public synchronized void resetPoll() {
    pollInterval = 1;
    interrupt();
  }

  
  @Override
  public void run() {
    // 1. check if we have a unique device id / secret code stored
    // 2. verify the device id / secret code with the server
    
    CookieManager cm;
    
    // check if we already have a session cookie
    try {
      URL url = new URL(
            new URL(service.getLoginURL()), "devices/");
      cm = doSessionCookie(url);
      
      // login
      String sharedKey = null;
      try {
        sharedKey = doLogin(url);
      } catch (IOException ioe) {
        service.notifySharedKey(ioe.getMessage());
      }
      if (sharedKey == null) return;
      
      // update UI
      service.notifySharedKey(sharedKey);
      
      // poll
      doPoll(url);
      
      if (service.wantLogout) {
        // logout
        doLogout(url);
      }
       
      onExit(url, cm);
      
    } catch (MalformedURLException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }
  

  public synchronized void finish() {
    this.finished = true;
    this.interrupt();
  }
  
  /**
   * Called on the end of network thread.
   * 
   * Let's also save our cookies now
   */
  protected void onExit(URL base_url, CookieManager cm) {

    URI uri;
    try {
      String storageString = null;
      
      uri = new URL(base_url.getProtocol(), base_url.getHost(),
        base_url.getPort(), "/").toURI();
      List<HttpCookie> cookies = cm.getCookieStore().get(uri);
      
      if (cookies.isEmpty())
        storageString = null;
      else 
        storageString = TextUtils.join(",", cookies);
      
      for (HttpCookie cookie: cookies) {
        Log.d("cloudkb", "Saved: " + cookie.toString());
        Log.d("cloudkb", Long.toString(cookie.getMaxAge()));
        Log.d("cloudkb", (cookie.getPath() == null) ? "(null)" : cookie.getPath());
        Log.d("cloudkb", (cookie.getDomain() == null) ? "(null)" : cookie.getDomain());
        Log.d("cloudkb", (cookie.getPortlist() == null) ? "(null)" : cookie.getPortlist());
      }
      
      SharedPreferences.Editor ed = service.getSharedPreferences("default", Context.MODE_PRIVATE)
        .edit();
      ed.putString("session_cookie", storageString);
      ed.commit();
    } catch (MalformedURLException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (URISyntaxException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    
    
    
    runAction(new Action() {
      @Override
      public Object run() {
        service.networkServerFinished();
        return null;
      }
    });
  }
  
  /**
   * Handles the reply from the server
   * 
   * FIXME: You don't need all the *MessageAck classes
   */
  private ArrayList<JSONObject> processEvents(JSONArray events) {
    MessageAck ma;
    ArrayList<MessageAck> responses = new ArrayList<MessageAck>();
    ArrayList<JSONObject> replies = new ArrayList<JSONObject>();
    
    for (int i=0; i<events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      String eventType = event.optString("type");
      
      if (event == null || eventType == null) {
        continue;
      }

	  // ignore sequence number for now
	  int seq = event.optInt("seq", -1);
      
      if (eventType.equals("key")) {
        KeyMessageAck kma = new KeyMessageAck(seq, Result.OK);
        String data = event.optString("data");
        
        if (data == null) {
          kma.res = Result.INVALID_INPUT;
        }
        else {
          boolean success = true;
          char mode = data.charAt(0);
          int code = Integer.parseInt(data.substring(1));
          if (mode == 'C') {
            // FIXME: can be a problem with extended unicode characters
            success = success && sendChar(code);
          } else {
            boolean pressed = mode == 'D';
            success = success && sendKey(code, pressed);
          }
  
          if (success) {
            kma.res = Result.OK;
          } else {
            kma.res = Result.ERROR;
          }
        }
        ma = kma;
      }
      else if (eventType.equals("settext")) {
        SetTextMessageAck stma = new SetTextMessageAck(seq, Result.OK);

        String data = event.optString("data");
        
        if (data == null) {
          stma.res = Result.INVALID_INPUT;
        }
        else {
          boolean success = replaceText(data);
          
          stma.res = success ? Result.OK : Result.ERROR;
        }
        
        ma = stma;
      }
      else if (eventType.equals("gettext")) { /* Server requests text from control */
        GetTextMessageAck gtma = new GetTextMessageAck(seq, Result.OK, null);
        
        gtma.data = sendText();
        if (gtma.data == null) {
          gtma.res = Result.ERROR;
        }
        
        ma = gtma;
      }
      else {
        ma = new MessageAck(seq, Result.INVALID_INPUT);
      }
      
      responses.add(ma);
    }
    
    for (MessageAck ack : responses) {
      if (ack.seq == -1)
        continue;
      
      // Add to reply queue
      try {
        replies.add(ack.toJSON());
      }
      catch (JSONException jse) {
        Log.e("cloudkeyboard", "Some replies lost due to exception in creating request.");
      }
    }
    return replies;
  }

  /**
   * A runnable that can actually return a result,
   * although the result isn't used so far */
  abstract class Action {
    public abstract Object run();
  }
  
  /**
   * Utility class to run tasks that you can wait on,
   * and use to collect the result of the task thereafter
   * */
  private class ActionRunner implements Runnable {
    private Action action;
    private boolean finished; 
    private Object actionResult;
    
    private void setAction(Action action) {
      this.action = action;
      this.finished = false;
    }
    
    public void run() {
      actionResult = action.run();
      synchronized (this) {
        finished = true;
        notify();
      }
    }
    
    public synchronized Object waitResult() {
      while (!finished) {
        try {
          wait();
        } catch (InterruptedException e) {
          actionResult = null;
          return null;
        }
      }
      return actionResult;
    }
  };
  // used by network thread
  abstract class KeyboardAction extends Action {
    @Override
    public Object run() {
      try {
        RemoteKeyListener listener = service.listener;
        if (listener != null) {
          return runAction(listener);
        }
      } catch (RemoteException e) {
        Debug.e("Exception on input method side, ignore", e);
      }
      return null;
    }
    abstract Object runAction(RemoteKeyListener listener) throws RemoteException;
  };
  
  // executed by network thread
  // We want it to block until the main thread replies
  boolean sendKey(final int code0, final boolean pressed) {
    final int code = convertKey(code0);
//    Log.d("wifikeyboard", "in: " + code0 + " out:" + code);
    
    Object success = runAction(new KeyboardAction() {
      @Override
      Object runAction(RemoteKeyListener listener) throws RemoteException {
        listener.keyEvent(code, pressed);
        return service; // not null for success
      }
    });
    return success != null;
  }
    
  // executed by network thread
  boolean sendChar(final int code) {
    Object success = runAction(new KeyboardAction() {
      @Override
      public Object runAction(RemoteKeyListener listener) throws RemoteException {
        listener.charEvent(code);
        return service; // not null
      }
    });
    return success != null;
  }
  
  String sendText() {
    Object result = runAction(new KeyboardAction() {
      @Override
      Object runAction(RemoteKeyListener listener) throws RemoteException {
        return listener.getText();
      }
    });
    return (String)result;
  }

  public boolean replaceText(final String string) {
    Object result = runAction(new KeyboardAction() {
      @Override
      Object runAction(RemoteKeyListener listener) throws RemoteException {
        
        return listener.setText(string) ? service : null;
      }
    });
    return result != null;
  }
  
  // executed by main thread
  /* TODO: stub */
  public void notifyClient(final String text) {
    // This should be the responsibility of the app server
  }

}
