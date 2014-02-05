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
package com.volosyukivan;

import static com.volosyukivan.KeycodeConvertor.convertKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.volosyukivan.KeyboardHttpServer.KeyboardAction;
import com.volosyukivan.MessageAck.Result;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

public class KeyboardHttpPoller extends Thread {

  // thread loop controller
  private boolean finished = false;
  
  // private for network thread
  private Selector selector;
  private HttpService service;
  
  // FIXME: get rid of?
  private Handler handler;
  
  // Buffer of replies
  Queue<JSONObject> replies;
  
  public KeyboardHttpPoller(HttpService service) {
    this.service = service;
  }
  
  ActionRunner actionRunner = new ActionRunner();
  
  
  /**
   * Invoke from network thread and execute action on main thread (synchronized).
   * @param action to run on main thread
   * @return object return by the action
   */
  public Object runAction(Action action) {
    actionRunner.setAction(action);
    handler.post(actionRunner);
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
  
  protected void setResponse(KeyboardHttpConnection con, ByteBuffer out) {
    try {
    con.key.interestOps(SelectionKey.OP_WRITE);
    con.outputBuffer = out;
    } catch (Exception e) {
      Log.e("wifikeyboard", "setResponse failed for hang connection", e);
    }
  }
  
  @Override
  public void run() {
//    Debug.d("HttpServer started listening");
    
    // 1. check if we have a unique device id / secret code stored
    // 2. verify the device id / secret code with the server
    
    SharedPreferences settings = service.getSharedPreferences("LOGIN", 0);
    String device_id = settings.getString("DEVICE_LOGIN", "");
    String device_key = settings.getString("DEVICE_KEY", "");
    
    if (device_id.isEmpty()) {
      // notify to user that he needs to login first
      
      return;
    }
    
    String uri = "http://some_server/devices/";
    // open a network connection to the destination
    HttpClient cl = new DefaultHttpClient();

    // login
    {
      HttpPost loginRequest = new HttpPost(uri);
      
      List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
      nameValuePairs.add(new BasicNameValuePair("login", device_id));
      nameValuePairs.add(new BasicNameValuePair("key", device_key));
      loginRequest.setEntity(new UrlEncodedFormEntity(nameValuePairs));
      
      // Execute HTTP Post Request
      HttpResponse response = null;
      try {
        response = cl.execute(loginRequest);
      }
      catch (IOException ioe) {
        // login failed. notify user with message
        return;
      }
      
      if (response.getStatusLine().getStatusCode() != 200) {
        // notify user to indicate login failure
        return;
      }
      
      // save the cookies for future use?
    }
      
    {
      // now, regularly poll the destination server
      final int MAX_POLL_INTERVAL = 1 << 14; // ~16 seconds
      int pollInterval = 1;
      while (true) {
        HttpPost dataRequest = new HttpPost(uri);
        JSONObject requestBody = new JSONObject();
        
        try {
          requestBody.put("action", "poll");
        }
        catch (JSONException jse) {
          Log.e("cloudkeyboard", "Why is there a JSON error here?");
        }
        
        JSONObject child;
        JSONArray repl = new JSONArray();
        // TODO: you don't need a queue here
        // since processEvents() runs in the same thread
        // instead, manage events such that if an exception
        // occurs, you can still re-send...
        while ( (child = replies.poll() ) != null ) {
          repl.put(child);
        }
        try {
          requestBody.put("replies", repl);
        }
        catch (JSONException jse) {
          Log.e("cloudkeyboard", "Why is there a JSON error here?");
        }

        try {
          dataRequest.setEntity(new StringEntity(requestBody.toString(2)));
        }
        catch (UnsupportedEncodingException uee) {
          Log.e("cloudkeyboard", uee.toString());
          dataRequest = null;
        }
        catch (JSONException jse) {
          Log.e("cloudkeyboard", jse.toString());
          dataRequest = null;
        }
        
        HttpResponse response = null;
        try {
          response = cl.execute(dataRequest);
        }
        catch (IOException ioe) {
          break;
        }
        
        // Now read the JSON reply... if it is a JSON reply
        JSONObject replyBody = null;
        try {
          // TODO: limit input size
          BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
          StringBuilder sb = new StringBuilder();
          String line;
          
          while ((line = br.readLine()) != null) {
            sb.append(line);
          }
          
          replyBody = new JSONObject(sb.toString());
        }
        catch (JSONException jse) {
          Log.e("cloudkeyboard", "Could not parse reply from server as JSON: " + jse.toString());
          continue;
        }
        catch (IOException ioe) {
          Log.e("cloudkeyboard", "I/O error while reading reply from server: " + ioe.toString());
        }

        JSONArray events;
        if (replyBody != null &&
            replyBody.has("events") &&
            (events = replyBody.optJSONArray("events")) != null) {
          pollInterval = 1;
          
          processEvents(events);
        }
        else {
          if (pollInterval*2 < MAX_POLL_INTERVAL) {
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
      cl = null; // end the client connection
    }
     
    onExit();
  }
  

  public synchronized void finish() {
    this.finished = true;
    this.interrupt();
  }
  
  /**
   * Called on the end of network thread.
   */
  protected void onExit() {
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
   */
  private int seqNum;
  private void processEvents(JSONArray events) {
    MessageAck ma;
    ArrayList<MessageAck> responses = new ArrayList<MessageAck>();
    
    for (int i=0; i<events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      String eventType = event.optString("type");
      
      if (event == null || eventType == null) {
        continue;
      }

      int seq = event.optInt("seq", -1);

      if (seq == -1)
        continue;

      if (seq < seqNum) {
        ma = new MessageAck(seq, Result.MULTIPLE_INPUT);
        responses.add(ma);
        continue;
      }
      
      if (eventType == "key") {
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
      else if (eventType == "settext") {
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
      else if (eventType == "gettext") { /* Server requests text from control */
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
    
    for (int i=0; i<responses.size(); i++) {
      MessageAck ack = responses.get(i);
      
      // For KeyMessageAck, we only acknowledge the last
      // in a run of KMAs
      if (ack instanceof KeyMessageAck) {
        if (i != responses.size() - 1 &&
            responses.get(i+1) instanceof KeyMessageAck) {
          continue;
        }
      }
      
      // Add to reply queue
      try {
        replies.add(ack.toJSON());
      }
      catch (JSONException jse) {
        Log.e("cloudkeyboard", "Some replies lost due to exception in creating request.");
      }
    }
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
}
