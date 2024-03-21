/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dashj.platform.sdk;

import org.dashj.platform.sdk.base.BaseObject;

public class IdentifierBytes32 extends BaseObject {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected IdentifierBytes32(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(IdentifierBytes32 obj) {
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
        exampleJNI.delete_IdentifierBytes32(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected long getCPointer() {
    return swigCPtr;
  }

  public void set_0(byte[] value) {
    exampleJNI.IdentifierBytes32__0_set(swigCPtr, this, value);
  }

  public byte[] get_0() {
    return exampleJNI.IdentifierBytes32__0_get(swigCPtr, this);
  }

  public IdentifierBytes32(byte[] identifierBytes) {
    this(exampleJNI.new_IdentifierBytes32(identifierBytes), true);
  }

}
