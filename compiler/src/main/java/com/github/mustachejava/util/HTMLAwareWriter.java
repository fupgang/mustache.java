package com.github.mustachejava.util;

import java.io.IOException;
import java.io.Writer;
import java.util.logging.Logger;

import static com.github.mustachejava.util.HTMLAwareWriter.Context.*;

/**
 * Manages a state machine that knows the context in an HTML document it is writing.
 */
public class HTMLAwareWriter extends Writer {

  private static Logger l = Logger.getLogger("HTMLAwareWriter");

  // Delegate
  private Writer writer;

  // Current state of the document
  private Context state;
  private Context quoteState;
  private Context bodyState;

  // Ringbuffer
  private RingBuffer ringBuffer = new RingBuffer();

  private static class RingBuffer {
    private static final int RING_SIZE = 6;
    private int length = 0;
    private char[] ring = new char[RING_SIZE];

    public void append(char c) {
      ring[length++ % RING_SIZE] = c;
    }

    public void clear() {
      length = 0;
    }

    public boolean compare(String s, boolean exact) {
      int len = s.length();
      if (exact && len != length) return false;
      if (len > RING_SIZE) {
        l.warning("Too long to compare: " + s);
        return false;
      }
      if (length >= len) {
        int j = 0;
        int position = length % RING_SIZE;
        for (int i = position - len; i < position; i++) {
          char c;
          if (i < 0) {
            c = ring[RING_SIZE + i];
          } else {
            c = ring[i];
          }
          if (s.charAt(j++) != c) {
            return false;
          }
        }
      }
      return true;
    }

  }

  // Buffer for tracking things
  public Context getState() {
    return state;
  }

  public enum Context {
    ATTRIBUTES,
    BODY,
    TAG,
    ATTR_NAME,
    ATTR_EQUAL,
    DQ_VALUE,
    SCRIPT_DQ_VALUE,
    TAG_NAME,
    END_TAG,
    END_TAG_NAME,
    ESCAPE,
    SCRIPT,
    SCRIPT_SQ_VALUE,
    SCRIPT_CHECK,
    AFTER_END_TAG_NAME,
    SQ_VALUE,
    NQ_VALUE,
    PRAGMA,
    COMMENT,
  }

  public HTMLAwareWriter(Writer writer) {
    this(writer, BODY);
  }

  public HTMLAwareWriter(Writer writer, Context state) {
    this.state = state;
    this.writer = writer;
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    transition(cbuf, off, len);
    writer.write(cbuf, off, len);
  }

  private void transition(char[] cbuf, int off, int len) {
    int end = off + len;
    for (int i = off; i < end; i++) {
      nextState(cbuf[i]);
      if (state == TAG_NAME || state == PRAGMA || state == COMMENT) {
        ringBuffer.append(cbuf[i]);
      }
    }
  }

  public void s(Context c) {
    state = c;
    ringBuffer.clear();
  }

  private void nextState(char c) {
    switch (state) {
      case ATTRIBUTES:
        attr(c);
        break;
      case BODY:
        body(c);
        break;
      case TAG:
        tag(c);
        break;
      case ATTR_NAME:
        attrName(c);
        break;
      case ATTR_EQUAL:
        attrEqual(c);
        break;
      case DQ_VALUE:
        dqValue(c);
        break;
      case SCRIPT_DQ_VALUE:
        scriptDqValue(c);
        break;
      case TAG_NAME:
        tagName(c);
        break;
      case END_TAG:
        endTag(c);
        break;
      case END_TAG_NAME:
        endTagName(c);
        break;
      case ESCAPE:
        escape();
        break;
      case SCRIPT:
        script(c);
        break;
      case SCRIPT_SQ_VALUE:
        scriptSqValue(c);
        break;
      case SCRIPT_CHECK:
        scriptCheck(c);
        break;
      case AFTER_END_TAG_NAME:
        afterEndTagName(c);
        break;
      case SQ_VALUE:
        sqValue(c);
        break;
      case NQ_VALUE:
        nqValue(c);
        break;
      case PRAGMA:
        pragma(c);
        break;
      case COMMENT:
        comment(c);
        break;
    }
  }

  private void scriptCheck(char c) {
    if (c == '/') {
      bodyState = BODY;
      s(END_TAG);
    } else if (ws(c)) {
    } else {
      s(SCRIPT);
    }
  }

  private void pragma(char c) {
    if (c == '-') {
      if (ringBuffer.compare("!-", false)) {
        s(COMMENT);
      }
    } else if (c == '>') {
      s(bodyState);
    }
  }

  private void scriptSqValue(char c) {
    if (c == '\\') {
      quoteState = state;
      s(ESCAPE);
    } else if (c == '\'') {
      s(SCRIPT);
    }
  }

  private void scriptDqValue(char c) {
    if (c == '\\') {
      quoteState = state;
      s(ESCAPE);
    } else if (c == '"') {
      s(SCRIPT);
    }
  }

  private void script(char c) {
    if (c == '"') {
      s(SCRIPT_DQ_VALUE);
    } else if (c == '\'') {
      s(SCRIPT_SQ_VALUE);
    } else if (c == '<') {
      s(SCRIPT_CHECK);
    }
  }

  private void afterEndTagName(char c) {
    if (ws(c)) {
    } else if (c == '>') {
      s(bodyState);
    } else error(c);
  }

  private void endTagName(char c) {
    if (namePart(c)) {
    } else if (c == '>') {
      s(bodyState);
    } else if (ws(c)) {
      s(AFTER_END_TAG_NAME);
    } else error(c);
  }

  private void endTag(char c) {
    if (nameStart(c)) {
      s(END_TAG_NAME);
    } else if (Character.isWhitespace(c)) {
    } else if (c == '>') {
      s(bodyState);
    } else error(c);
  }

  private boolean nameStart(char c) {
    return Character.isJavaIdentifierStart(c);
  }

  private void comment(char c) {
    if (c == '>') {
      if (ringBuffer.compare("--", false)) {
        s(bodyState);
      }
    }
  }

  private void nqValue(char c) {
    if (ws(c)) {
      s(ATTRIBUTES);
    } else if (c == '<') {
      error(c);
    } else if (c == '>') {
      s(bodyState);
    }
  }

  private void escape() {
    s(quoteState);
  }

  private void sqValue(char c) {
    if (c == '\\') {
      s(ESCAPE);
      quoteState = SQ_VALUE;
    } else if (c == '\'') {
      s(ATTRIBUTES);
    } else if (c == '<' || c == '>') {
      error(c);
    }
  }

  private void dqValue(char c) {
    if (c == '\\') {
      s(ESCAPE);
      quoteState = DQ_VALUE;
    } else if (c == '\"') {
      s(ATTRIBUTES);
    } else if (c == '<' || c == '>') {
      error(c);
    }
  }

  private void attrEqual(char c) {
    if (c == '"') {
      s(DQ_VALUE);
    } else if (c == '\'') {
      s(SQ_VALUE);
    } else if (ws(c)) {
    } else {
      s(NQ_VALUE);
    }
  }

  private boolean ws(char c) {
    return Character.isWhitespace(c);
  }

  private void attrName(char c) {
    if (namePart(c)) {
    } else if (c == '=') {
      s(ATTR_EQUAL);
    } else if (ws(c)) {
    } else if (c == '>') {
      s(bodyState);
    } else error(c);
  }

  private void attr(char c) {
    if (nameStart(c)) {
      s(ATTR_NAME);
    } else if (c == '>') {
      s(bodyState);
    } else if (c == '/') {
      s(AFTER_END_TAG_NAME);
    } else if (ws(c)) {
    } else error(c);
  }

  private void tagName(char c) {
    if (namePart(c)) {
    } else if (ws(c)) {
      setBodyTag();
      s(ATTRIBUTES);
    } else if (c == '>') {
      setBodyTag();
      s(bodyState);
    }
  }

  private boolean namePart(char c) {
    return Character.isJavaIdentifierPart(c) || c == '-';
  }

  private void setBodyTag() {
    if (ringBuffer.compare("script", true)) {
      bodyState = SCRIPT;
    } else bodyState = BODY;
  }

  private void tag(char c) {
    if (nameStart(c)) {
      s(TAG_NAME);
    } else if (c == '/') {
      s(END_TAG);
    } else if (c == '!') {
      s(PRAGMA);
    } else if (ws(c)) {
    } else error(c);
  }

  private void error(char c) {
    l.warning("Invalid: " + new StringBuilder().append(c) + " (" + state + ")");
    s(bodyState);
  }

  private void body(char c) {
    if (c == '<') {
      bodyState = state;
      s(TAG);
    }
  }

  @Override
  public void flush() throws IOException {
    writer.flush();
  }

  @Override
  public void close() throws IOException {
    flush();
    writer.close();
  }
}

