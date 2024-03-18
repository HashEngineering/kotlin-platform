/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dash.sdk;

import org.dash.sdk.base.BaseObject;

public class IdentityV0 extends BaseObject {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected IdentityV0(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(IdentityV0 obj) {
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
        exampleJNI.delete_IdentityV0(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected long getCPointer() {
    return swigCPtr;
  }

  public void setId(Identifier value) {
    exampleJNI.IdentityV0_id_set(swigCPtr, this, Identifier.getCPtr(value), value);
  }

  public Identifier getId() {
    long cPtr = exampleJNI.IdentityV0_id_get(swigCPtr, this);
    return (cPtr == 0) ? null : new Identifier(cPtr, false);
  }

  public void setRevision(Revision value) {
    exampleJNI.IdentityV0_revision_set(swigCPtr, this, Revision.getCPtr(value), value);
  }

  public Revision getRevision() {
    long cPtr = exampleJNI.IdentityV0_revision_get(swigCPtr, this);
    return (cPtr == 0) ? null : new Revision(cPtr, false);
  }

  public int getPublicKeyCount() {
    return exampleJNI.IdentityV0_getPublicKeyCount(swigCPtr, this);
  }

  public IdentityPublicKeyV0 getPublicKey(long index) {
    long cPtr = exampleJNI.IdentityV0_getPublicKey(swigCPtr, this, index);
    return (cPtr == 0) ? null : new IdentityPublicKeyV0(cPtr, false);
  }

  public IdentityPublicKeyV0 getPublicKeyById(long id) {
    long cPtr = exampleJNI.IdentityV0_getPublicKeyById(swigCPtr, this, id);
    return (cPtr == 0) ? null : new IdentityPublicKeyV0(cPtr, false);
  }

  public long getBalance() {
    return exampleJNI.IdentityV0_getBalance(swigCPtr, this);
  }

}
