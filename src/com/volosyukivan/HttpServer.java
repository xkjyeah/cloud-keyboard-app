package com.volosyukivan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import android.os.Handler;
import android.os.RemoteException;
import android.text.Html;
import android.view.KeyEvent;

public class HttpServer extends Thread {
  private ServerSocket socket;
  private HttpService service;
  private Handler handler;
  private HttpRequestParser httpRequestParser = new HttpRequestParser();
  static final int FOCUS = 1024;
  private boolean isDone;
  private int seqNum = 0;
  private boolean connected;
  private boolean delivered;

  HttpServer(HttpService service, ServerSocket socket) {
    this.service = service;
    this.socket = socket;
    this.handler = new Handler();
  }

  @Override
  public void run() {
    Debug.d("HttpServer started listening");

    while (!isDone()) {
      try {
        Socket s = socket.accept();
        processHttpRequest(s);
      } catch (IOException e) {
        Debug.e("request failed", e);
      } catch (NumberFormatException e) {
        Debug.e("request failed", e);
      }
    }
    try {
      socket.close();
    } catch (IOException e) {
      Debug.e("closing listening socket", e);
    }
  }
  
  private final synchronized boolean isDone() {
    return isDone;
  }
  
  public synchronized void finish() {
    isDone = true;
  }
  
  public void sendImage(OutputStream os, int resid) throws IOException {
    os.write(
        ("HTTP/1.0 200 OK\n" +
        "Content-Type: image/gif\n\n")
        .getBytes());
    InputStream is2 = service.getResources().openRawResource(resid);
    byte[] image = new byte[10240];
    os.write(image, 0, is2.read(image));
  }

  private void processHttpRequest(Socket s) throws IOException {
    // Debug.d("got request");
    InputStream is = s.getInputStream();
    String req = httpRequestParser.getRequest(is);
    OutputStream os = s.getOutputStream();
    Debug.d("got key event: " + req);
    
    if (req.equals("")) {
      Debug.d("sending html page");
      os.write(
          ("HTTP/1.0 200 OK\n" +
          "Content-Type: text/html; charset=ISO-8859-1\n\n")
          .getBytes());
      String page = service.htmlpage.replace("12345", Integer.toString(seqNum + 1));
      byte[] bytes = page.getBytes();
      os.write(bytes, 0, bytes.length);
      s.close();
      sendKey(FOCUS, true);
      return;
    }
    
    
    if (req.equals("bg.gif")) {
      sendImage(os, R.raw.bg);
      s.close();
      return;
    }

    if (req.equals("icon.png")) {
      sendImage(os, R.raw.icon);
      s.close();
      return;
    }
    
    boolean success = true;
    boolean event = false;
    
    try {
      String[] ev = req.split(",");
      int seq = Integer.parseInt(ev[0]);
      int numKeysRequired = seq - seqNum;
      if (numKeysRequired <= 0) return;
      int numKeysAvailable = ev.length - 1;
      int numKeys = Math.min(numKeysAvailable, numKeysRequired);
      
      for (int i = numKeys; i >= 1; i--) {
        Debug.d("Event: " + ev[i]);
        char mode = ev[i].charAt(0);
        int code = Integer.parseInt(ev[i].substring(1));
        if (mode == 'C') {
          // FIXME: can be a problem with extended unicode characters
          success = success && sendChar((char) code);
        } else {
          boolean pressed = mode == 'D';
          success = success && sendKey(code, pressed);
        }
        event = true;
      }
      seqNum = seq;
    }
    finally {
      if (!event) {
        os.write("multi".getBytes("UTF-8"));
        Debug.d("multi");
      } else if (success) {
        os.write("ok".getBytes("UTF-8"));
        Debug.d("ok");
      } else {
        os.write("problem".getBytes("UTF-8"));
        Debug.d("problem");
      }
      s.close();
    }
  }
  
  private boolean sendKey(final int code0, final boolean pressed) {
    delivered = false;
    final int code = convertKey(code0);
    handler.post(new Runnable() {
      @Override
      public void run() {
        boolean connected0 = false;
        try {
          // Debug.d("key going to listener");
          if (service.listener != null) {
            service.listener.keyEvent(code, pressed);
            connected0 = true;
          }
        } catch (RemoteException e) {
          Debug.e("Exception on input method side, ignore", e);
        }
        notifyDelivered(connected0);
      }
    });
    return waitNotifyDelivered();
  }
  
  private boolean sendChar(final char code) {
    delivered = false;
    handler.post(new Runnable() {
      @Override
      public void run() {
        boolean connected0 = false;
        try {
          // Debug.d("key going to listener");
          if (service.listener != null) {
            service.listener.charEvent(code);
            connected0 = true;
          }
        } catch (RemoteException e) {
          Debug.e("Exception on input method side, ignore", e);
        }
        notifyDelivered(connected0);
      }
    });
    return waitNotifyDelivered();
  }
  
  protected synchronized void notifyDelivered(boolean connected0) {
    connected = connected0;
    delivered = true;
    notifyAll();
  }
  private synchronized boolean waitNotifyDelivered() {
    while (!delivered) {
      try {
        wait();
      } catch (InterruptedException e) {
        connected = false;
      }
    }
    return connected;
  }

  private int convertKey(int code) {
    // public static final int KEYCODE_A = 29;
    // ...
    // public static final int KEYCODE_Z = 54;
    if (code >= 65 && code <= 90) {
      return code - 65 + KeyEvent.KEYCODE_A;
    }

    // public static final int KEYCODE_0 = 7;
    // ...
    // public static final int KEYCODE_9 = 16;
    if (code >= 48 && code <= 57) {
      return code - 48 + KeyEvent.KEYCODE_0;
    }

    switch (code) {
      case 9: return KeyEvent.KEYCODE_TAB;
      case 32: return KeyEvent.KEYCODE_SPACE;
      case 188: return KeyEvent.KEYCODE_COMMA;
      case 190: return KeyEvent.KEYCODE_PERIOD;
      case 13: return KeyEvent.KEYCODE_ENTER;
      case 219: return KeyEvent.KEYCODE_LEFT_BRACKET;
      case 221: return KeyEvent.KEYCODE_RIGHT_BRACKET;
      case 220: return KeyEvent.KEYCODE_BACKSLASH;
      case 186: return KeyEvent.KEYCODE_SEMICOLON;
      case 222: return KeyEvent.KEYCODE_APOSTROPHE;
      case 8: return KeyEvent.KEYCODE_DEL;
      case 189: return KeyEvent.KEYCODE_MINUS;
      case 187: return KeyEvent.KEYCODE_EQUALS;
      case 191: return KeyEvent.KEYCODE_SLASH;
      case 18: return KeyEvent.KEYCODE_ALT_LEFT;
      case 16: return KeyEvent.KEYCODE_SHIFT_LEFT;

      // public static final int KEYCODE_DPAD_UP = 19;
      // public static final int KEYCODE_DPAD_DOWN = 20;
      // public static final int KEYCODE_DPAD_LEFT = 21;
      // public static final int KEYCODE_DPAD_RIGHT = 22;
      // public static final int KEYCODE_DPAD_CENTER = 23;
      // arrow keys
      case 38: return KeyEvent.KEYCODE_DPAD_UP;
      case 40: return KeyEvent.KEYCODE_DPAD_DOWN;
      case 37: return KeyEvent.KEYCODE_DPAD_LEFT;
      case 39: return KeyEvent.KEYCODE_DPAD_RIGHT;
      // Insert
      case 112: return KeyEvent.KEYCODE_DPAD_CENTER;
      case 45: return KeyEvent.KEYCODE_DPAD_CENTER;
      
      
      // ESC
      case 27: return KeyEvent.KEYCODE_BACK;
      // Home
      case 36: return KeyEvent.KEYCODE_HOME;
      case 113: return KeyEvent.KEYCODE_MENU;
      // case x: return KeyEvent.KEYCODE_CALL;
      // case x: return KeyEvent.KEYCODE_ENDCALL;
      
      // F9, F10
      case 121: return KeyEvent.KEYCODE_VOLUME_UP;
      case 120: return KeyEvent.KEYCODE_VOLUME_DOWN;
      case FOCUS: return FOCUS;

      default: return KeyEvent.KEYCODE_UNKNOWN;
    }

    // case x: return KeyEvent.KEYCODE_SOFT_LEFT;
    // case x: return KeyEvent.KEYCODE_SOFT_RIGHT;
    // case x: return KeyEvent.KEYCODE_STAR;
    // case x: return KeyEvent.KEYCODE_POUND;
    // case x: return KeyEvent.KEYCODE_POWER;
    // case x: return KeyEvent.KEYCODE_CAMERA;
    // case x: return KeyEvent.KEYCODE_CLEAR;
    // case x: return KeyEvent.KEYCODE_ALT_RIGHT;
    // case x: return KeyEvent.KEYCODE_SHIFT_RIGHT;
    // case x: return KeyEvent.KEYCODE_SYM;
    // case x: return KeyEvent.KEYCODE_EXPLORER;
    // case x: return KeyEvent.KEYCODE_ENVELOPE;
    // case x: return KeyEvent.KEYCODE_GRAVE;
    // case x: return KeyEvent.KEYCODE_AT;
    // case x: return KeyEvent.KEYCODE_NUM;
    // case x: return KeyEvent.KEYCODE_HEADSETHOOK;
    // case x: return KeyEvent.KEYCODE_FOCUS;
    // case x: return KeyEvent.KEYCODE_PLUS;
    // case x: return KeyEvent.KEYCODE_NOTIFICATION;
    // case x: return KeyEvent.KEYCODE_SEARCH;
    // case x: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    // case x: return KeyEvent.KEYCODE_MEDIA_STOP;
    // case x: return KeyEvent.KEYCODE_MEDIA_NEXT;
    // case x: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    // case x: return KeyEvent.KEYCODE_MEDIA_REWIND;
    // case x: return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
    // case x: return KeyEvent.KEYCODE_MUTE;
    //
    // META_ALT_ON = 2;
    // META_ALT_LEFT_ON = 16;
    // META_ALT_RIGHT_ON = 32;
    // META_SHIFT_ON = 1;
    // META_SHIFT_LEFT_ON = 64;
    // META_SHIFT_RIGHT_ON = 128;
    // META_SYM_ON = 4;
  }

}