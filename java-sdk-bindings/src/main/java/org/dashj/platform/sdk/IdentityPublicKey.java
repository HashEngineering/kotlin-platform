/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dashj.platform.sdk;

import org.dashj.platform.sdk.base.BaseObject;

public class IdentityPublicKey extends BaseObject {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected IdentityPublicKey(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(IdentityPublicKey obj) {
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
        exampleJNI.delete_IdentityPublicKey(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected long getCPointer() {
    return swigCPtr;
  }

  public void setTag(IdentityPublicKey_Tag value) {
    exampleJNI.IdentityPublicKey_tag_set(swigCPtr, this, value.swigValue());
  }

  public IdentityPublicKey_Tag getTag() {
    return IdentityPublicKey_Tag.swigToEnum(exampleJNI.IdentityPublicKey_tag_get(swigCPtr, this));
  }

  public void setV0(IdentityPublicKeyV0 value) {
    exampleJNI.IdentityPublicKey_v0_set(swigCPtr, this, IdentityPublicKeyV0.getCPtr(value), value);
  }

  public IdentityPublicKeyV0 getV0() {
    long cPtr = exampleJNI.IdentityPublicKey_v0_get(swigCPtr, this);
    return (cPtr == 0) ? null : new IdentityPublicKeyV0(cPtr, false);
  }

}
