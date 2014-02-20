package com.simd;

import org.json.JSONException;
import org.json.JSONObject;

public class MessageAck {
  public int seq;
  public Result res;
  
  public enum Result {
    OK (0),
    ERROR (1),
    MULTIPLE_INPUT (2),
    INVALID_INPUT(3);
    
    private int res;
    
    private Result(int i) {
      this.res = i;
    }
    
    public int toInt() {
      return this.res;
    }
  };
  
  public MessageAck(int seq, Result res) {
    this.seq = seq;
    this.res = res;
  }
  public MessageAck(Result res) {
    this.seq = 0;
    this.res = res;
  }
  
  public JSONObject toJSON() throws JSONException {
    JSONObject jso = new JSONObject();
    jso.put("seq", seq);
    jso.put("result", res.toInt());
    return jso;
  }
}

class KeyMessageAck extends MessageAck {
  public KeyMessageAck(int seq, Result res) {
    super(seq, res);
  }
  public KeyMessageAck(Result res){
    super(res);
  }
}

class GetTextMessageAck extends MessageAck {
  public String data;
  
  public GetTextMessageAck(int seq, Result res, String data) {
    super(seq, res);
    this.data = data;
  }
  public GetTextMessageAck(Result res, String data){
    this (0, res, data);
  }

  @Override
  public JSONObject toJSON() throws JSONException {
    JSONObject jso = super.toJSON();
    jso.put("data", (data == null)?JSONObject.NULL : data);
    return jso;
  }
}

class SetTextMessageAck extends MessageAck {
  public String data;
  
  public SetTextMessageAck(int seq, Result res) {
    super(seq, res);
  }

  public SetTextMessageAck(Result res){
    super(res);
  }
}