/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dash.sdk;

import org.dash.sdk.base.BaseObject;

public class Result_ok_dpp_identity_identity_Identity_err_String extends BaseObject {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected Result_ok_dpp_identity_identity_Identity_err_String(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(Result_ok_dpp_identity_identity_Identity_err_String obj) {
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
        exampleJNI.delete_Result_ok_dpp_identity_identity_Identity_err_String(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected long getCPointer() {
    return swigCPtr;
  }

  public void setOk(Identity value) {
    exampleJNI.Result_ok_dpp_identity_identity_Identity_err_String_ok_set(swigCPtr, this, Identity.getCPtr(value), value);
  }

  public Identity getOk() {
    long cPtr = exampleJNI.Result_ok_dpp_identity_identity_Identity_err_String_ok_get(swigCPtr, this);
    return (cPtr == 0) ? null : new Identity(cPtr, false);
  }

  public void setError(String value) {
    exampleJNI.Result_ok_dpp_identity_identity_Identity_err_String_error_set(swigCPtr, this, value);
  }

  public String getError() {
    return exampleJNI.Result_ok_dpp_identity_identity_Identity_err_String_error_get(swigCPtr, this);
  }

}
