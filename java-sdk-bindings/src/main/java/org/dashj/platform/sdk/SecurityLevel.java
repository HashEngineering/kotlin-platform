/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 4.0.2
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package org.dashj.platform.sdk;

public final class SecurityLevel {
  public final static SecurityLevel MASTER = new SecurityLevel("MASTER", exampleJNI.MASTER_get());
  public final static SecurityLevel CRITICAL = new SecurityLevel("CRITICAL", exampleJNI.CRITICAL_get());
  public final static SecurityLevel HIGH = new SecurityLevel("HIGH", exampleJNI.HIGH_get());
  public final static SecurityLevel MEDIUM = new SecurityLevel("MEDIUM", exampleJNI.MEDIUM_get());

  public final int swigValue() {
    return swigValue;
  }

  public String toString() {
    return swigName;
  }

  public static SecurityLevel swigToEnum(int swigValue) {
    if (swigValue < swigValues.length && swigValue >= 0 && swigValues[swigValue].swigValue == swigValue)
      return swigValues[swigValue];
    for (int i = 0; i < swigValues.length; i++)
      if (swigValues[i].swigValue == swigValue)
        return swigValues[i];
    throw new IllegalArgumentException("No enum " + SecurityLevel.class + " with value " + swigValue);
  }

  private SecurityLevel(String swigName) {
    this.swigName = swigName;
    this.swigValue = swigNext++;
  }

  private SecurityLevel(String swigName, int swigValue) {
    this.swigName = swigName;
    this.swigValue = swigValue;
    swigNext = swigValue+1;
  }

  private SecurityLevel(String swigName, SecurityLevel swigEnum) {
    this.swigName = swigName;
    this.swigValue = swigEnum.swigValue;
    swigNext = this.swigValue+1;
  }

  private static SecurityLevel[] swigValues = { MASTER, CRITICAL, HIGH, MEDIUM };
  private static int swigNext = 0;
  private final int swigValue;
  private final String swigName;
}

