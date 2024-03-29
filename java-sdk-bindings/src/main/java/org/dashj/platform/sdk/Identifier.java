/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dashj.platform.sdk;

import org.dashj.platform.sdk.base.BaseObject;

public class Identifier extends BaseObject {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected Identifier(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(Identifier obj) {
    return (obj == null) ? 0 : obj.swigCPtr;
  }

  @SuppressWarnings("deprecation")
  protected void finalize() {
    delete();
  }

  public synchronized void delete() {
    if (swigCPtr != 0) {
      if (swigCMemOwn) {
        swigCMemOwn = false;
        exampleJNI.delete_Identifier(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected long getCPointer() {
    return swigCPtr;
  }

  public void set_0(IdentifierBytes32 value) {
    exampleJNI.Identifier__0_set(swigCPtr, this, IdentifierBytes32.getCPtr(value), value);
  }

  public IdentifierBytes32 get_0() {
    long cPtr = exampleJNI.Identifier__0_get(swigCPtr, this);
    return (cPtr == 0) ? null : new IdentifierBytes32(cPtr, false);
  }

  public Identifier(byte[] byteArray) {
    this(exampleJNI.new_Identifier(byteArray), true);
  }

}
