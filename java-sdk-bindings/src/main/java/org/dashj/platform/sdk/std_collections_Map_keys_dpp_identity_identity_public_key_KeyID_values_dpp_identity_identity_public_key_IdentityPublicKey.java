/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dashj.platform.sdk;

import org.dashj.platform.sdk.base.BaseObject;

public class std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey extends BaseObject {
  private transient long swigCPtr;
  protected transient boolean swigCMemOwn;

  protected std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey(long cPtr, boolean cMemoryOwn) {
    swigCMemOwn = cMemoryOwn;
    swigCPtr = cPtr;
  }

  protected static long getCPtr(std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey obj) {
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
        exampleJNI.delete_std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey(swigCPtr);
      }
      swigCPtr = 0;
    }
  }

  protected long getCPointer() {
    return swigCPtr;
  }

  public void setCount(long value) {
    exampleJNI.std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey_count_set(swigCPtr, this, value);
  }

  public long getCount() {
    return exampleJNI.std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey_count_get(swigCPtr, this);
  }

  public void setKeys(SWIGTYPE_p_p_dpp_identity_identity_public_key_KeyID value) {
    exampleJNI.std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey_keys_set(swigCPtr, this, SWIGTYPE_p_p_dpp_identity_identity_public_key_KeyID.getCPtr(value));
  }

  public SWIGTYPE_p_p_dpp_identity_identity_public_key_KeyID getKeys() {
    long cPtr = exampleJNI.std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey_keys_get(swigCPtr, this);
    return (cPtr == 0) ? null : new SWIGTYPE_p_p_dpp_identity_identity_public_key_KeyID(cPtr, false);
  }

  public void setValues(SWIGTYPE_p_p_dpp_identity_identity_public_key_IdentityPublicKey value) {
    exampleJNI.std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey_values_set(swigCPtr, this, SWIGTYPE_p_p_dpp_identity_identity_public_key_IdentityPublicKey.getCPtr(value));
  }

  public SWIGTYPE_p_p_dpp_identity_identity_public_key_IdentityPublicKey getValues() {
    long cPtr = exampleJNI.std_collections_Map_keys_dpp_identity_identity_public_key_KeyID_values_dpp_identity_identity_public_key_IdentityPublicKey_values_get(swigCPtr, this);
    return (cPtr == 0) ? null : new SWIGTYPE_p_p_dpp_identity_identity_public_key_IdentityPublicKey(cPtr, false);
  }

}
