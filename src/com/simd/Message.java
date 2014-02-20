package com.simd;

import java.io.Serializable;

public class Message implements Serializable {
  private static final long serialVersionUID = -1244011690155191117L;
  public int seq;
  
  public Message(int seq) {
    this.seq = seq;
  }
}

class KeyMessage extends Message {
  private static final long serialVersionUID = -3556024057552328478L;
  
  public String data;

  public KeyMessage(int seq, String data) {
    super(seq);
    this.data = data;
  }
}

class GetTextMessage extends Message {
  private static final long serialVersionUID = 1490674881202910248L;

  public GetTextMessage(int seq) {
    super(seq);
  }
}

class SetTextMessage extends Message {
  private static final long serialVersionUID = 5088395615550558788L;
  public String data;
  
  public SetTextMessage(int seq, String data) {
    super(seq);
    this.data = data;
  }
}