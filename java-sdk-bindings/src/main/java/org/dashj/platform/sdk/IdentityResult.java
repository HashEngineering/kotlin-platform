/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dashj.platform.sdk;

import org.dashj.platform.sdk.base.BaseObject;

public class IdentityResult extends BaseObject {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected IdentityResult(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(IdentityResult obj) {
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
        exampleJNI.delete_IdentityResult(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected long getCPointer() {
    return swigCPtr;
  }

  public void setOk(Identity value) {
    exampleJNI.IdentityResult_ok_set(swigCPtr, this, Identity.getCPtr(value), value);
  }

  public Identity getOk() {
    long cPtr = exampleJNI.IdentityResult_ok_get(swigCPtr, this);
    return (cPtr == 0) ? null : new Identity(cPtr, false);
  }

  public void setError(String value) {
    exampleJNI.IdentityResult_error_set(swigCPtr, this, value);
  }

  public String getError() {
    return exampleJNI.IdentityResult_error_get(swigCPtr, this);
  }

}